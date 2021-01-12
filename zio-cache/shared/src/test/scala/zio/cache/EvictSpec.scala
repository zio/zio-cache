package zio.cache

import zio.cache.CacheGen._
import zio.test._
import zio.test.Assertion._

object EvictSpec extends DefaultRunnableSpec {

  def spec = suite("EvictSpec")(
    testM("&& is associative") {
      check(genEvict, genEvict, genEvict, genEntry) { (evict1, evict2, evict3, entry) =>
        val left  = (evict1 && evict2) && evict3
        val right = evict1 && (evict2 && evict3)
        assert(left.evict(entry))(equalTo(right.evict(entry)))
      }
    },
    testM("&& is commutative") {
      check(genEvict, genEvict, genEntry) { (evict1, evict2, entry) =>
        val left  = evict1 && evict2
        val right = evict2 && evict1
        assert(left.evict(entry))(equalTo(right.evict(entry)))
      }
    },
    testM("all is an identity element with respect to &&") {
      check(genEvict, genEntry) { (evict, entry) =>
        val left  = Evict.all && evict
        val right = evict && Evict.all
        assert(left.evict(entry))(equalTo(right.evict(entry)))
      }
    },
    testM("|| is associative") {
      check(genEvict, genEvict, genEvict, genEntry) { (evict1, evict2, evict3, entry) =>
        val left  = (evict1 || evict2) || evict3
        val right = evict1 || (evict2 || evict3)
        assert(left.evict(entry))(equalTo(right.evict(entry)))
      }
    },
    testM("|| is commutative") {
      check(genEvict, genEvict, genEntry) { (evict1, evict2, entry) =>
        val left  = evict1 || evict2
        val right = evict2 || evict1
        assert(left.evict(entry))(equalTo(right.evict(entry)))
      }
    },
    testM("none is an identity element with respect to ||") {
      check(genEvict, genEntry) { (evict, entry) =>
        val left  = Evict.none || evict
        val right = evict || Evict.none
        assert(left.evict(entry))(equalTo(right.evict(entry)))
      }
    }
  )
}
