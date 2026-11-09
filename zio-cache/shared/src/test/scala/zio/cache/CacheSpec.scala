package zio.cache

import zio._
import zio.test.Assertion._
import zio.test._

object CacheSpec extends ZIOSpecDefault {

  def hash(x: Int): Int => UIO[Int] =
    y => ZIO.succeed((x ^ y).hashCode)

  val identity: Int => UIO[Int] =
    ZIO.succeed(_)

  def spec: Spec[TestEnvironment with Scope, Any] = suite("CacheSpec")(
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
    test("ensure get is not hanging when we interrupt right after a get in another fiber") {
      val n = 100000
      for {
        cache <- Cache.make(10, Duration.Infinity, Lookup((_: Unit) => ZIO.unit))
        task1  = (cache.invalidate(()) *> cache.get(())).fork.flatMap(_.interrupt)
        _     <- ZIO.collectAll(List.fill(n)(task1))
        _     <- (cache.get(()).exit.timeoutFail("hanging")(3.seconds)).replicateZIODiscard(n)
      } yield assertCompletes
    } @@ TestAspect.withLiveClock @@ TestAspect.timeout(20.seconds),
    test("cache hits should not be incremented for expired entries") {
      for {
        cache      <- Cache.make(100, 1.second, Lookup(identity))
        _          <- cache.get(42)
        _          <- TestClock.adjust(2.seconds)
        _          <- cache.get(42)
        cacheStats <- cache.cacheStats
        hits        = cacheStats.hits
        misses      = cacheStats.misses
      } yield assertTrue(hits == 0L) &&
        assertTrue(misses == 2L)
    },
    suite("`contains` method")(
      test("should return false for expired entries") {
        for {
          cache     <- Cache.make(100, 1.second, Lookup(identity))
          _         <- cache.get(42)
          contains1 <- cache.contains(42)
          _         <- TestClock.adjust(2.seconds)
          contains2 <- cache.contains(42)
        } yield assertTrue(contains1) && assertTrue(!contains2)
      },
      test("should return true during ongoing lookup") {
        for {
          started  <- Promise.make[Nothing, Unit]
          promise  <- Promise.make[Nothing, Int]
          cache    <- Cache.make(100, Duration.Infinity, Lookup((_: Int) => started.succeed(()) *> promise.await))
          _        <- cache.get(42).fork
          _        <- started.await
          contains <- cache.contains(42)
          _        <- promise.succeed(42)
        } yield assertTrue(contains)
      }
    ),
    suite("key tracking")(
      test("does not grow when an entry expires and is reloaded") {
        for {
          cache <- Cache.make(10, 1.second, Lookup(identity))
          _     <- (cache.get(42) *> TestClock.adjust(2.seconds)).repeatN(99)
        } yield assertTrue(keyCount(cache) <= 2)
      },
      test("does not grow when an entry is invalidated and reloaded") {
        for {
          cache <- Cache.make(10, Duration.Infinity, Lookup(identity))
          _     <- (cache.get(42) *> cache.invalidate(42)).repeatN(99)
        } yield assertTrue(keyCount(cache) <= 2)
      },
      test("does not grow when an entry is refreshed") {
        for {
          cache <- Cache.make(10, Duration.Infinity, Lookup(identity))
          _     <- (cache.refresh(42) *> cache.get(42)).repeatN(99)
        } yield assertTrue(keyCount(cache) == 1)
      },
      test("evicts the least recently used entry") {
        for {
          cache <- Cache.make(2, Duration.Infinity, Lookup(identity))
          _     <- cache.get(1) *> cache.get(2) *> cache.get(1) *> cache.get(3)
          one   <- cache.contains(1)
          two   <- cache.contains(2)
          three <- cache.contains(3)
        } yield assertTrue(one, !two, three)
      },
      test("evicts an entry added by refresh") {
        for {
          cache <- Cache.make(1, Duration.Infinity, Lookup(identity))
          _     <- cache.refresh(1) *> cache.get(2)
          one   <- cache.contains(1)
          two   <- cache.contains(2)
        } yield assertTrue(!one, two)
      },
      test("evicts an entry that changes state while it is being evicted") {
        for {
          runtime <- ZIO.runtime[Any]
          calls   <- Ref.make(0)
          // A new value on every call, so the refreshed entry is not equal to the one being evicted
          cache <- Cache.make(1, Duration.Infinity, Lookup((_: HookKey) => calls.updateAndGet(_ + 1)))
          one    = new HookKey(1)
          _     <- cache.get(one)
          // Eviction hashes `one` to read its entry, then again to remove it: refresh the entry in between
          // (refreshing a stored entry does not track an access, so it does not re-enter the eviction)
          _ <- ZIO.succeed(one.onHash(2) {
                 Unsafe.unsafe(implicit unsafe => runtime.unsafe.run(cache.refresh(one)).getOrThrowFiberFailure())
               })
          _      <- cache.get(new HookKey(2))
          size   <- cache.size
          hasOne <- cache.contains(one)
        } yield assertTrue(size == 1, !hasOne, keyCount(cache) == 1)
      }
    )
  )

  private def keyCount(cache: Cache[?, ?, ?]): Int =
    cache.asInstanceOf[Cache.CacheImplementation[?, ?, ?, ?, ?]].keyCount
}
