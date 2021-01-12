package zio.cache

/**
 * A `Evict` is used in deciding whether or not to keep a new entry that is looked up,
 * as well as deciding whether or not to keep existing entries that may have changed a lot since
 * they were loaded into the cache (too old, too infrequently used, etc.). It makes hard
 * decisions on cache entries: they should be purged, or they should be retained.
 */
final case class Evict[-Value](evict: Entry[Value] => Boolean) { self =>
  def &&[Value1 <: Value](that: Evict[Value1]): Evict[Value1] =
    Evict[Value1]((v) => self.evict(v) && that.evict(v))

  def ||[Value1 <: Value](that: Evict[Value1]): Evict[Value1] =
    Evict[Value1]((v) => self.evict(v) || that.evict(v))

  def unary_! : Evict[Value] =
    Evict[Value]((v) => !self.evict(v))
}
object Evict {

  val all: Evict[Any] = Evict[Any](_ => true)

  def equalTo[A](value: A): Evict[A] = Evict[A](entry => entry.value == value)

  def greaterThan(size: Long): Evict[Any] =
    Evict[Any](entry => entry.entryStats.curSize >= size)

  val none: Evict[Any] = !all
}
