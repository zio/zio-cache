package zio.cache

import zio._
import zio.duration._
import zio.test.Assertion._
import zio.test._

object CacheSpec extends DefaultRunnableSpec {

  def hash(x: Int): Int => UIO[Int] =
    y => ZIO.succeed((x ^ y).hashCode)

  def spec: ZSpec[Environment, Failure] = suite("CacheSpec")(
    testM("cacheStats") {
      checkM(Gen.anyInt) { salt =>
        for {
          cache      <- Cache.make(100, Duration.Infinity, Lookup(hash(salt)))
          _          <- ZIO.foreachPar((1 to 100).map(_ / 2))(cache.get)
          cacheStats <- cache.cacheStats
          hits        = cacheStats.hits
          misses      = cacheStats.misses
        } yield assert(hits)(equalTo(49L)) &&
          assert(misses)(equalTo(51L))
      }
    },
    testM("invalidate") {
      checkM(Gen.anyInt) { salt =>
        for {
          cache    <- Cache.make(100, Duration.Infinity, Lookup(hash(salt)))
          _        <- ZIO.foreach(1 to 100)(cache.get)
          _        <- cache.invalidate(42)
          contains <- cache.contains(42)
        } yield assert(contains)(isFalse)
      }
    },
    testM("invalidateAll") {
      checkM(Gen.anyInt) { salt =>
        for {
          cache <- Cache.make(100, Duration.Infinity, Lookup(hash(salt)))
          _     <- ZIO.foreach(1 to 100)(cache.get)
          _     <- cache.invalidateAll
          size  <- cache.size
        } yield assert(size)(equalTo(0))
      }
    },
    suite("lookup")(
      testM("sequential") {
        checkM(Gen.anyInt) { salt =>
          for {
            cache    <- Cache.make(100, Duration.Infinity, Lookup(hash(salt)))
            actual   <- ZIO.foreach(1 to 100)(cache.get)
            expected <- ZIO.foreach(1 to 100)(hash(salt))
          } yield assert(actual)(equalTo(expected))
        }
      },
      testM("concurrent") {
        checkM(Gen.anyInt) { salt =>
          for {
            cache    <- Cache.make(100, Duration.Infinity, Lookup(hash(salt)))
            actual   <- ZIO.foreachPar(1 to 100)(cache.get)
            expected <- ZIO.foreachPar(1 to 100)(hash(salt))
          } yield assert(actual)(equalTo(expected))
        }
      },
      testM("capacity") {
        checkM(Gen.anyInt) { salt =>
          for {
            cache    <- Cache.make(10, Duration.Infinity, Lookup(hash(salt)))
            actual   <- ZIO.foreachPar(1 to 100)(cache.get)
            expected <- ZIO.foreachPar(1 to 100)(hash(salt))
          } yield assert(actual)(equalTo(expected))
        }
      }
    ),
    testM("size") {
      checkM(Gen.anyInt) { salt =>
        for {
          cache <- Cache.make(10, Duration.Infinity, Lookup(hash(salt)))
          _     <- ZIO.foreach((1 to 100))(cache.get)
          size  <- cache.size
        } yield assert(size)(equalTo(10))
      }
    }
  )
}
