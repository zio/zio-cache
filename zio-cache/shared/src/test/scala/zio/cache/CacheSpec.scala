package zio.cache

import zio._
import zio.test.Assertion._
import zio.test._

object CacheSpec extends ZIOSpecDefault {

  def hash(x: Int): Int => UIO[Int] =
    y => ZIO.succeed((x ^ y).hashCode)

  def spec: Spec[Environment, Any] = suite("CacheSpec")(
    test("cacheStats") {
      check(Gen.int) { salt =>
        for {
          cache      <- Cache.make(100, Duration.Infinity, Lookup(hash(salt)))
          _          <- ZIO.foreachParDiscard((1 to 100).map(_ / 2))(cache.get)
          cacheStats <- cache.cacheStats
          hits        = cacheStats.hits
          misses      = cacheStats.misses
        } yield assertTrue(hits == 49L) &&
          assertTrue(misses == 51L)
      }
    },
    test("invalidate") {
      check(Gen.int) { salt =>
        for {
          cache    <- Cache.make(100, Duration.Infinity, Lookup(hash(salt)))
          _        <- ZIO.foreachDiscard(1 to 100)(cache.get)
          _        <- cache.invalidate(42)
          contains <- cache.contains(42)
        } yield assert(contains)(isFalse)
      }
    },
    test("invalidateAll") {
      check(Gen.int) { salt =>
        for {
          cache <- Cache.make(100, Duration.Infinity, Lookup(hash(salt)))
          _     <- ZIO.foreachDiscard(1 to 100)(cache.get)
          _     <- cache.invalidateAll
          size  <- cache.size
        } yield assertTrue(size == 0)
      }
    },
    suite("lookup")(
      test("sequential") {
        check(Gen.int) { salt =>
          for {
            cache    <- Cache.make(100, Duration.Infinity, Lookup(hash(salt)))
            actual   <- ZIO.foreach(1 to 100)(cache.get)
            expected <- ZIO.foreach(1 to 100)(hash(salt))
          } yield assertTrue(actual == expected)
        }
      },
      test("concurrent") {
        check(Gen.int) { salt =>
          for {
            cache    <- Cache.make(100, Duration.Infinity, Lookup(hash(salt)))
            actual   <- ZIO.foreachPar(1 to 100)(cache.get)
            expected <- ZIO.foreachPar(1 to 100)(hash(salt))
          } yield assertTrue(actual == expected)
        }
      },
      test("capacity") {
        check(Gen.int) { salt =>
          for {
            cache    <- Cache.make(10, Duration.Infinity, Lookup(hash(salt)))
            actual   <- ZIO.foreachPar(1 to 100)(cache.get)
            expected <- ZIO.foreachPar(1 to 100)(hash(salt))
          } yield assertTrue(actual == expected)
        }
      }
    ),
    suite("`refresh` method")(
      test("should update the cache with a new value") {
        def inc(n: Int) = n * 10
        def retrieve(multiplier: Ref[Int])(key: Int) =
          multiplier
            .updateAndGet(inc)
            .map(key * _)

        val seed = 1
        val key  = 123
        for {
          ref   <- Ref.make(seed)
          cache <- Cache.make(1, Duration.Infinity, Lookup(retrieve(ref)))
          val1  <- cache.get(key)
          _     <- cache.refresh(key)
          _     <- cache.get(key)
          val2  <- cache.get(key)
        } yield assertTrue(val1 == inc(key)) && assertTrue(val2 == inc(val1))
      },
      test("should update the cache with a new value even if the last `get` or `refresh` failed") {

        val error = new RuntimeException("Must be a multiple of 3")

        def inc(n: Int) = n + 1
        def retrieve(number: Ref[Int])(key: Int) =
          number
            .updateAndGet(inc)
            .flatMap {
              case n if n % 3 == 0 =>
                ZIO.fail(error)
              case n =>
                ZIO.succeed(key * n)
            }

        val seed = 2
        val key  = 1
        for {
          ref      <- Ref.make(seed)
          cache    <- Cache.make(1, Duration.Infinity, Lookup(retrieve(ref)))
          failure1 <- cache.get(key).either
          _        <- cache.refresh(key)
          val1     <- cache.get(key).either
          _        <- cache.refresh(key)
          failure2 <- cache.refresh(key).either
          _        <- cache.refresh(key)
          val2     <- cache.get(key).either
        } yield assert(failure1)(isLeft(equalTo(error))) &&
          assert(failure2)(isLeft(equalTo(error))) &&
          assert(val1)(isRight(equalTo(4))) &&
          assert(val2)(isRight(equalTo(7)))
      },
      test("should get the value if the key doesn't exist in the cache") {
        check(Gen.int) { salt =>
          val cap = 100
          for {
            cache  <- Cache.make(cap, Duration.Infinity, Lookup(hash(salt)))
            count0 <- cache.size
            _      <- ZIO.foreachDiscard(1 to cap)(cache.refresh)
            count1 <- cache.size
          } yield assertTrue(count0 == 0) && assertTrue(count1 == cap)
        }
      }
    ),
    test("size") {
      check(Gen.int) { salt =>
        for {
          cache <- Cache.make(10, Duration.Infinity, Lookup(hash(salt)))
          _     <- ZIO.foreachDiscard(1 to 100)(cache.get)
          size  <- cache.size
        } yield assertTrue(size == 10)
      }
    },
    test("time to live using fixed duration") {
      for {
        cache <- Cache.makeWith(100, Lookup { n: Int => ZIO.succeed(List(1, 2, 3)) }, TimeToLive.fixed(1.second))
        v1 <- cache.get(1) // miss
        v2 <- cache.get(1) // hit
        _ <- TestClock.adjust(1001.millis)
        v3 <- cache.get(1) // hit but expired, thus another miss
        cacheStats <- cache.cacheStats
        hits        = cacheStats.hits
        misses      = cacheStats.misses
      } yield {
        assertTrue(v1 eq v2) && // v1 and v2 retrieve the same cached object
        assertTrue(hits == 2L) &&
        assertTrue(v1 ne v3) && // v3 points to a different object since v1 has expired
        assertTrue(misses == 2L)
      }
    },
    test("jitter computation") {
      check(Gen.double(0, 1)) { min =>
        check(Gen.double(min, 1)) { max =>
          TimeToLive.jittered(min, max, 1.second).compute(Exit.succeed(())).map { duration =>
            assertTrue(duration >= 1.second * min) &&
            assertTrue(duration <= 1.second * max)
          }
        }
      }
    }
  )
}
