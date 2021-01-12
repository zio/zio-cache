package zio.cache

import zio.cache.CacheGen._
import zio.test._
import zio.test.Assertion._

object CachePolicySpec extends DefaultRunnableSpec {

  def spec = suite("CachePolicySpec")(
    testM("++ is associative") {
      check(genCachingPolicy, genCachingPolicy, genCachingPolicy, genEntry, genEntry) {
        (cachingPolicy1, cachingPolicy2, cachingPolicy3, l, r) =>
          val left  = (cachingPolicy1 ++ cachingPolicy2) ++ cachingPolicy3
          val right = cachingPolicy1 ++ (cachingPolicy2 ++ cachingPolicy3)
          assert(left.compare(l, r))(equalTo(right.compare(l, r)))
      }
    },
    testM("ordering of composing eviction policies is irrelevant") {
      check(genCachingPolicy, genEvict, genEntry, genEntry) { (cachingPolicy, evict, l, r) =>
        val left  = cachingPolicy ++ CachingPolicy.fromEvict(evict)
        val right = CachingPolicy.fromEvict(evict) ++ cachingPolicy
        assert(left.compare(l, r))(equalTo(right.compare(l, r)))
      }
    }
  )
}
