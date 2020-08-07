package zio.cache

import java.time._

import zio._

/*

 * Maximum size of cache (e.g. 10,000 elements)
 * Eviction around various criteria
   - Fixed date expiration
   - Time since creation? 
   - Time since last access? (how often is it used?)
   - Time since refresh? (how old is it?)
   - Entry-level statistics? (Hits/misses)
   - References to the entry? (any references left?)
   - Size of entry? (how much memory does it consume?)
   - Cache-wide statistics?
   - Value-based criteria?
 * Notification of eviction
 * Ability to "write" loaded values, e.g. store on disk after retrieving remote values
 * LRU / Adapative / etc.
*/

final case class EntryStats(
  added      : Instant,
  accessed   : Instant,
  loaded     : Instant,
  hits       : Long, 
  misses     : Long, 
  loads      : Long,
  curSize    : Long,
  accSize    : Long,
  accLoading : Duration) {
  def total: Long = hits + misses

  def size: Long = accSize / loads
}

final case class CacheStats(
  entryCount    : Int,
  memorySize    : Long,
  hits          : Long, 
  misses        : Long, 
  loads         : Long, 
  evictions     : Long, 
  totalLoadTime : Duration)

final case class Entry[+Value](cacheStats: CacheStats, entryStats: EntryStats, value: Value)


/*

CACHE: SIZE 4

Entry3 (retention policy: don't care)
Entry5 (retention policy: don't care)
Entry1 (retention policy: don't care)
Entry4 (retention policy: definitely evict)
Entry2 (retention policy: definitely evict)

Order from LEAST VALUE to MOST VALUE


|    |    |    |    |    |    |    |    |    |    |    |    |    |    |    |    |
                    ^
                    |
                    |

*/
/**
  * A `Evict` is used in deciding whether or not to keep a new entry that is looked up,
  * as well as deciding whether or not to keep existing entries that may have changed a lot since 
  * they were loaded into the cache (too old, too infrequently used, etc.). It makes hard 
  * decisions on cache entries: they should be purged, or they should be retained.
  */
final case class Evict[-Value](evict: (Instant, Entry[Value]) => Boolean) { self =>
  def && [Value1 <: Value](that: Evict[Value1]): Evict[Value1] = 
    Evict[Value1]((n, v) => self.evict(n, v) && that.evict(n, v))

  def || [Value1 <: Value](that: Evict[Value1]): Evict[Value1] = 
    Evict[Value1]((n, v) => self.evict(n, v) || that.evict(n, v))

  def unary_! : Evict[Value] = 
    Evict[Value]((n, v) => !self.evict(n, v))
}
object Evict {
  val all: Evict[Any] = Evict[Any]((_, _) => true)

  val none: Evict[Any] = !all

  def olderThan(duration: Duration): Evict[Any] = 
    Evict[Any] { (now, entry) => 
      val life = Duration.between(entry.entryStats.loaded, now)

      life.compareTo(duration) >= 0
    }

  def greaterThan(size: Long): Evict[Any] = 
    Evict[Any]((_, entry) => entry.entryStats.curSize >= size)
}

sealed trait CacheWorth { self =>
  import CacheWorth._ 

  def ++ (that: => CacheWorth): CacheWorth = 
    if (self == Equal) that else self

  def flip: CacheWorth = 
    if (self == Left) Right else if (self == Right) Left else Equal
}
object CacheWorth {
  case object Left  extends CacheWorth 
  case object Right extends CacheWorth 
  case object Equal extends CacheWorth
}

/**
  * A `CachingPolicy` is used to decide which values to expire from the cache when the 
  * cache reaches its maximum size and there is a new potential cache entry computed.
  */
sealed abstract class CachingPolicy[-Value] { self =>
  protected def ordering(now: Instant, left: Entry[Value], right: Entry[Value]): CacheWorth

  def compare(now: Instant, left: Entry[Value], right: Entry[Value]): CacheWorth = {
    if (evict.evict(now, left) && !evict.evict(now, right)) CacheWorth.Right 
    else if (!evict.evict(now, left) && evict.evict(now, right)) CacheWorth.Left 
    else ordering(now, left, right)
  }

  def evict: Evict[Value]

  def ++[Value1 <: Value](that: CachingPolicy[Value1]): CachingPolicy[Value1] = 
    self.andThen(that)

  def andThen[Value1 <: Value](that: CachingPolicy[Value1]): CachingPolicy[Value1] = 
    CachingPolicy((now, left, right) =>
      self.compare(now, left, right) ++ that.compare(now, left, right),
      self.evict && that.evict
    )

  def flip: CachingPolicy[Value] = 
    CachingPolicy((n, l, r) => self.compare(n, r, l).flip, !self.evict)
}
object CachingPolicy {
  private def apply[Value](compare0: (Instant, Entry[Value], Entry[Value]) => CacheWorth, Evict0: Evict[Value]): CachingPolicy[Value] = 
    new CachingPolicy[Value] {
      def ordering(now: Instant, left: Entry[Value], right: Entry[Value]): CacheWorth = 
        compare0(now, left, right)

      def evict: Evict[Value] = Evict0
    }
  
  val byHits: CachingPolicy[Any] = fromOrdering(_.entryStats.hits)

  val byLastAccess: CachingPolicy[Any] = fromOrdering(_.entryStats.accessed)

  val bySize: CachingPolicy[Any] = fromOrdering(_.entryStats.curSize)

  def fromOrdering[A](proj: Entry[Any] => A)(implicit ord: Ordering[A]): CachingPolicy[Any] = 
    CachingPolicy({ (_, left, right) => 
      val l = proj(left)
      val r = proj(right)

      if (ord.lt(l, r)) CacheWorth.Right
      else if (ord.gt(l, r)) CacheWorth.Left
      else CacheWorth.Equal
      },
      Evict.none
    )

  def fromOrderingValue[A](implicit ord: Ordering[A]): CachingPolicy[A] = 
    CachingPolicy((_, l, r) => 
      if (ord.lt(l.value, r.value)) CacheWorth.Right
      else if (ord.gt(l.value, r.value)) CacheWorth.Left 
      else CacheWorth.Equal,
      Evict.none
    )

  def fromPredicate[A](evict: (Instant, Entry[A]) => Boolean): CachingPolicy[A] = 
    CachingPolicy((_, _, _) => CacheWorth.Equal, Evict(evict))

  def fromEvict[A](policy: Evict[A]): CachingPolicy[A] = 
    fromPredicate((now, entry) => !policy.evict(now, entry))
}

object Example {
  import CachingPolicy._

  val evict = 
    Evict.olderThan(Duration.ofHours(1L)) &&  // newer than 1 hour
    Evict.greaterThan(100 * 1024 * 1024)      // smaller than 100 MB

  val policy = 
    byLastAccess ++ bySize ++ fromEvict(evict)
}

/**
 * A function that can lookup a value given a key, or possibly fail while trying.
 */
final case class Lookup[-Key, -R, +E, +Value](value: Key => ZIO[R, E, Value]) extends (Key => ZIO[R, E, Value]) {
  def apply(v1: Key): ZIO[R, E, Value] = value(v1)
}

/**
 * A `Cache[Key, Err, Value]` is an interface to a cache with keys of type `Key` and values of type 
 * `Value`. A cacche has a single method to retrieve an entry from the cache given its key. If an 
 * entry is not inside the cache, then the lookup function associated with the cache will be used to 
 * retrieve the entry. If the caching policy dictates the retrieved entry should be stored and 
 * there is sufficient room in the cache, then the value will be stored inside the cache until it is 
 * later expired, as per the specified caching policy.
 */
trait Cache[-Key, +Error, +Value] {
  def get(k: Key): IO[Error, Value]
}
object Cache {
  /**
    * Creates a cache with a specified capacity and lookup function.
    */
  def make[Key, R, E, Value](capacity: Int, policy: CachingPolicy[Value], lookup: Lookup[Key, R, E, Value]): ZIO[R, Nothing, Cache[Key, E, Value]] = 
    ZIO.environment[R].map { env =>
      val _ = capacity 
      val _ = policy

      new Cache[Key, E, Value] {
        def get(key: Key): IO[E, Value] = lookup(key).provide(env)
      }
    }
}