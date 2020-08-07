package zio.cache

import zio.cache.CacheGen._
import zio.test._
import zio.test.Assertion._

object EvictSpec extends DefaultRunnableSpec {

  def spec = suite("EvictSpec")(
    testM("&& is associative") {
      check(genEvict, genEvict, genEvict, genInstant, genEntry) { (evict1, evict2, evict3, now, entry) =>
        val left  = (evict1 && evict2) && evict3
        val right = evict1 && (evict2 && evict3)
        assert(left.evict(now, entry))(equalTo(right.evict(now, entry)))
      }
    },
    testM("&& is commutative") {
      check(genEvict, genEvict, genInstant, genEntry) { (evict1, evict2, now, entry) =>
        val left  = evict1 && evict2
        val right = evict2 && evict1
        assert(left.evict(now, entry))(equalTo(right.evict(now, entry)))
      }
    },
    testM("all is an identity element with respect to &&") {
      check(genEvict, genInstant, genEntry) { (evict, now, entry) =>
        val left  = Evict.all && evict
        val right = evict && Evict.all
        assert(left.evict(now, entry))(equalTo(right.evict(now, entry)))
      }
    },
    testM("|| is associative") {
      check(genEvict, genEvict, genEvict, genInstant, genEntry) { (evict1, evict2, evict3, now, entry) =>
        val left  = (evict1 || evict2) || evict3
        val right = evict1 || (evict2 || evict3)
        assert(left.evict(now, entry))(equalTo(right.evict(now, entry)))
      }
    },
    testM("|| is commutative") {
      check(genEvict, genEvict, genInstant, genEntry) { (evict1, evict2, now, entry) =>
        val left  = evict1 || evict2
        val right = evict2 || evict1
        assert(left.evict(now, entry))(equalTo(right.evict(now, entry)))
      }
    },
    testM("none is an identity element with respect to ||") {
      check(genEvict, genInstant, genEntry) { (evict, now, entry) =>
        val left  = Evict.none || evict
        val right = evict || Evict.none
        assert(left.evict(now, entry))(equalTo(right.evict(now, entry)))
      }
    }
  )
}
