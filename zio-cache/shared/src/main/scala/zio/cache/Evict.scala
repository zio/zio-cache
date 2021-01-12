package zio.cache

import java.time.{ Duration, Instant }

/**
 * A `Evict` is used in deciding whether or not to keep a new entry that is looked up,
 * as well as deciding whether or not to keep existing entries that may have changed a lot since
 * they were loaded into the cache (too old, too infrequently used, etc.). It makes hard
 * decisions on cache entries: they should be purged, or they should be retained.
 */
final case class Evict[-Value](evict: (Instant, Entry[Value]) => Boolean) { self =>
  def &&[Value1 <: Value](that: Evict[Value1]): Evict[Value1] =
    Evict[Value1]((n, v) => self.evict(n, v) && that.evict(n, v))

  def ||[Value1 <: Value](that: Evict[Value1]): Evict[Value1] =
    Evict[Value1]((n, v) => self.evict(n, v) || that.evict(n, v))

  def unary_! : Evict[Value] =
    Evict[Value]((n, v) => !self.evict(n, v))
}
object Evict {

  val all: Evict[Any] = Evict[Any]((_, _) => true)

  def equalTo[A](value: A): Evict[A] = Evict[A]((_, entry) => entry.value == value)

  def greaterThan(size: Long): Evict[Any] =
    Evict[Any]((_, entry) => entry.entryStats.curSize >= size)

  def olderThan(duration: Duration): Evict[Any] =
    Evict[Any] { (now, entry) =>
      val life = Duration.between(entry.entryStats.loaded, now)

      life.compareTo(duration) >= 0
    }

  def fromPredicate[A](evict: (Instant, Entry[A]) => Boolean): Evict[A] =
    Evict(evict)

  val none: Evict[Any] = !all
}
