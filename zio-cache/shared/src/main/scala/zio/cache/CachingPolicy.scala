package zio.cache

import java.time.Instant

/**
 * A `CachingPolicy` is used to decide which values to expire from the cache
 * when the cache reaches its maximum size and there is a new potential cache
 * entry computed.
 */
final case class CachingPolicy[-Value](priority: Priority[Value], evict: Evict[Value]) { self =>

  def compare(now: Instant, left: Entry[Value], right: Entry[Value]): CacheWorth =
    if (evict.evict(now, left) && !evict.evict(now, right)) CacheWorth.Right
    else if (!evict.evict(now, left) && evict.evict(now, right)) CacheWorth.Left
    else priority.compare(left, right)

  def ++[Value1 <: Value](that: CachingPolicy[Value1]): CachingPolicy[Value1] =
    self andThen that

  def andThen[Value1 <: Value](that: CachingPolicy[Value1]): CachingPolicy[Value1] =
    CachingPolicy(self.priority ++ that.priority, self.evict || that.evict)

  def flip: CachingPolicy[Value] =
    CachingPolicy(priority.flip, !evict)
}

object CachingPolicy {

  val byHits: CachingPolicy[Any] =
    fromOrdering(_.entryStats.hits)

  val byLastAccess: CachingPolicy[Any] =
    fromOrdering(_.entryStats.accessed)

  val bySize: CachingPolicy[Any] =
    fromOrdering(_.entryStats.curSize)

  def fromOrdering[A](proj: Entry[Any] => A)(implicit ord: Ordering[A]): CachingPolicy[Any] =
    fromPriority(Priority.fromOrdering(proj))

  def fromOrderingValue[A](implicit ord: Ordering[A]): CachingPolicy[A] =
    fromPriority(Priority.fromOrderingValue)

  def fromPredicate[A](evict: (Instant, Entry[A]) => Boolean): CachingPolicy[A] =
    fromEvict(Evict(evict))

  def fromEvict[A](evict: Evict[A]): CachingPolicy[A] =
    CachingPolicy(Priority.any, evict)

  def fromPriority[A](priority: Priority[A]): CachingPolicy[A] =
    CachingPolicy(priority, Evict.none)
}
