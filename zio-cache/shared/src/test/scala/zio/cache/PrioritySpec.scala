package zio.cache

import zio.cache.CacheGen._
import zio.test._
import zio.test.Assertion._

object PrioritySpec extends DefaultRunnableSpec {

  def spec = suite("PrioritySpec")(
    testM("++ is associative") {
      check(genPriority, genPriority, genPriority, genInstant, genEntry, genEntry) {
        (priority1, priority2, priority3, now, l, r) =>
          val left  = (priority1 ++ priority2) ++ priority3
          val right = priority1 ++ (priority2 ++ priority3)
          assert(left.compare(now, l, r))(equalTo(right.compare(now, l, r)))
      }
    },
    testM("any is an identity element with respect to ++") {
      check(genPriority, genInstant, genEntry, genEntry) { (priority, now, l, r) =>
        val left  = Priority.any ++ priority
        val right = priority ++ Priority.any
        assert(left.compare(now, l, r))(equalTo(priority.compare(now, l, r))) &&
        assert(right.compare(now, l, r))(equalTo(priority.compare(now, l, r)))
      }
    }
  )
}
