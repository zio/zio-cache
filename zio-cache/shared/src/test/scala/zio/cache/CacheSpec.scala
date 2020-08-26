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
        cache <- Cache.make(20, CachingPolicy.byLastAccess, identityLookup)
        value <- cache.get("Sherlock")
      } yield assert(value)(equalTo("Sherlock"))
    },
    testM("get increases cache size") {
      for {
        cache <- Cache.make(20, CachingPolicy.byLastAccess, identityLookup)
        _     <- cache.get("Sherlock")
        size  <- cache.size 
      } yield assert(size)(equalTo(1))
    }
  )
}
