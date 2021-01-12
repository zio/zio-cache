package zio.cache

import zio._
import zio.test._
import zio.test.Assertion._

object CacheSpec extends DefaultRunnableSpec {
  val identityLookup =
    Lookup[String, Any, Nothing, String]((key: String) => ZIO.succeed(key))

  def spec = suite("CacheSpec")(
    testM("get invokes lookup function") {
      for {
        cache <- Cache.make(20, CachingPolicy.byLastAccess, Ttl.never, identityLookup)
        value <- cache.get("Sherlock")
      } yield assert(value)(equalTo("Sherlock"))
    },
    testM("get increases cache size") {
      for {
        cache <- Cache.make(20, CachingPolicy.byLastAccess, Ttl.never, identityLookup)
        _     <- cache.get("Sherlock")
        size  <- cache.size
      } yield assert(size)(equalTo(1))
    },
    testM("cache stores no more entries than capacity") {
      for {
        cache <- Cache.make(1, CachingPolicy.byLastAccess, Ttl.never, identityLookup)
        _     <- cache.get("Sherlock") *> cache.get("Holmes")
        size  <- cache.size
      } yield assert(size)(equalTo(1))
    },
    testM("cache will not store values that should be evicted") {
      for {
        cache <- Cache.make(20, CachingPolicy(Priority.any, Evict.equalTo("Sherlock")), Ttl.never, identityLookup)
        _     <- cache.get("Sherlock") *> cache.get("Holmes")
        size  <- cache.size
      } yield assert(size)(equalTo(1))
    },
    testM("cache will respecting priority when evicting on a full cache") {
      for {
        cache <- Cache.make(1, CachingPolicy(Priority.fromOrderingValue[String], Evict.none), Ttl.never, identityLookup)
        _     <- cache.get("Apple") *> cache.get("Banana")
        rez1  <- cache.contains("Banana")
        rez2  <- cache.contains("Apple")
        size  <- cache.size
      } yield assert(rez1)(isTrue) && assert(rez2)(isFalse) && assert(size)(equalTo(1))
    }
  )
}
