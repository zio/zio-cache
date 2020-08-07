package zio.cache

import zio.cache.CacheGen._
import zio.test._
import zio.test.Assertion._

object CachePolicySpec extends DefaultRunnableSpec {

  def spec = suite("CachePolicySpec")(
    testM("++ is associative") {
      check(genCachingPolicy, genCachingPolicy, genCachingPolicy, genInstant, genEntry, genEntry) {
        (cachingPolicy1, cachingPolicy2, cachingPolicy3, now, l, r) =>
          val left  = (cachingPolicy1 ++ cachingPolicy2) ++ cachingPolicy3
          val right = cachingPolicy1 ++ (cachingPolicy2 ++ cachingPolicy3)
          assert(left.compare(now, l, r))(equalTo(right.compare(now, l, r)))
      }
    },
    testM("ordering of composing eviction policies is irrelevant") {
      check(genCachingPolicy, genEvict, genInstant, genEntry, genEntry) { (cachingPolicy, evict, now, l, r) =>
        val left  = cachingPolicy ++ CachingPolicy.fromEvict(evict)
        val right = CachingPolicy.fromEvict(evict) ++ cachingPolicy
        assert(left.compare(now, l, r))(equalTo(right.compare(now, l, r)))
      }
    }
  )
}
