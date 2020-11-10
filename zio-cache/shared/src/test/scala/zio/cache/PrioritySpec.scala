package zio.cache

import zio.cache.CacheGen._
import zio.test._
import zio.test.Assertion._

object PrioritySpec extends DefaultRunnableSpec {

  def spec = suite("PrioritySpec")(
    testM("++ is associative") {
      check(genPriority, genPriority, genPriority, genEntry, genEntry) { (priority1, priority2, priority3, l, r) =>
        val left  = (priority1 ++ priority2) ++ priority3
        val right = priority1 ++ (priority2 ++ priority3)
        assert(left.compare(l, r))(equalTo(right.compare(l, r)))
      }
    },
    testM("any is an identity element with respect to ++") {
      check(genPriority, genEntry, genEntry) { (priority, l, r) =>
        val left  = Priority.any ++ priority
        val right = priority ++ Priority.any
        assert(left.compare(l, r))(equalTo(priority.compare(l, r))) &&
        assert(right.compare(l, r))(equalTo(priority.compare(l, r)))
      }
    }
  )
}
