package zio.cache

import zio._
import zio.test._
import zio.test.Assertion._

object CacheStatsSpec extends DefaultRunnableSpec {
  val identityLookup =
    Lookup[String, Any, Nothing, String]((key: String) => ZIO.succeed(key))

  def spec = suite("CacheStatsSpec")(
    testM("evictions") {
      for {
        cache     <- Cache.make(1, CachingPolicy.byLastAccess, identityLookup)
        _         <- cache.get("Sherlock")
        _         <- cache.get("Holmes")
        evictions <- cache.evictions
      } yield assert(evictions)(equalTo(1L))
    },
    testM("hits") {
      for {
        cache <- Cache.make(20, CachingPolicy.byLastAccess, identityLookup)
        _     <- cache.get("Sherlock")
        _     <- cache.get("Sherlock")
        hits  <- cache.hits
      } yield assert(hits)(equalTo(1L))
    },
    testM("cache loads") {
      for {
        cache <- Cache.make(20, CachingPolicy.byLastAccess, identityLookup)
        _     <- cache.get("Sherlock")
        _     <- cache.get("Sherlock")
        loads <- cache.loads
      } yield assert(loads)(equalTo(1L))
    },
    testM("entry loads") {
      for {
        cache <- Cache.make(1, CachingPolicy.byLastAccess, identityLookup)
        _     <- cache.get("Sherlock")
        _     <- cache.get("Holmes")
        _     <- cache.get("Sherlock")
        loads <- cache.entryStats("Sherlock")
      } yield assert(loads)(isSome(hasField("loads", _.loads, equalTo(2L))))
    },
    testM("misses") {
      for {
        cache  <- Cache.make(20, CachingPolicy.byLastAccess, identityLookup)
        _      <- cache.get("Sherlock")
        misses <- cache.misses
      } yield assert(misses)(equalTo(1L))
    },
    testM("totalLoadTime") {
      for {
        cache         <- Cache.make(20, CachingPolicy.byLastAccess, identityLookup)
        _             <- cache.get("Sherlock")
        totalLoadTime <- cache.totalLoadTime
      } yield assert(totalLoadTime.toNanos)(isGreaterThan(0L))
    }
  )
}
