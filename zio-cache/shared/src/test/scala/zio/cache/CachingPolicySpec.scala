package zio.cache

import zio.cache.CacheGen._
import zio.test._
import zio.test.Assertion._

object CachingPolicySpec extends DefaultRunnableSpec {

  def spec = suite("CachePolicySpec")(
    testM("++ is associative") {
      check(getCachingPolicy, getCachingPolicy, getCachingPolicy, genEntry, genEntry) {
        (cachingPolicy1, cachingPolicy2, cachingPolicy3, l, r) =>
          val left  = (cachingPolicy1 ++ cachingPolicy2) ++ cachingPolicy3
          val right = cachingPolicy1 ++ (cachingPolicy2 ++ cachingPolicy3)

          // TODO: got rid of any now-based assertions
          assert(left.compare(l, r))(equalTo(right.compare(l, r)))
      }
    }
  )
}
