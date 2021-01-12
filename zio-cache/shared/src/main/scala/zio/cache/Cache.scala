package zio.cache

import java.time.{ Duration, Instant }

import zio.{ Exit, IO, Promise, Ref, UIO, ZIO }

/**
 * A `Cache[Key, Error, Value]` is an interface to a cache with keys of type
 * `Key` and values of type  `Value`. A cache has a single method to retrieve
 * an entry from the cache given its key. If an  entry is not inside the cache,
 * then the lookup function associated with the cache will be used to retrieve
 * the entry. If the caching policy dictates the retrieved entry should be
 * stored and there is sufficient room in the cache, then the value will be
 * stored inside the cache until it is  later expired, as per the specified
 * caching policy.
 */
trait Cache[-Key, +Error, +Value] {
  def get(k: Key): IO[Error, Value]

  def contains(k: Key): UIO[Boolean]

  def size: UIO[Int]

  def cacheStats: UIO[CacheStats]

  def entryStats(key: Key): UIO[Option[EntryStats]]

  final def entryCount: UIO[Int] =
    cacheStats.map(_.entryCount)

  final def evictions: UIO[Long] =
    cacheStats.map(_.evictions)

  final def hits: UIO[Long] =
    cacheStats.map(_.hits)

  final def loads: UIO[Long] =
    cacheStats.map(_.loads)

  final def memorySize: UIO[Long] =
    cacheStats.map(_.memorySize)

  final def misses: UIO[Long] =
    cacheStats.map(_.misses)

  final def totalLoadTime: UIO[Duration] =
    cacheStats.map(_.totalLoadTime)
}

object Cache {

  /**
   * Creates a cache with a specified capacity and lookup function.
   */
  def make[Key, R, E, Value](
    capacity: Int,
    policy: CachingPolicy[Value],
    lookup: Lookup[Key, R, E, Value]
  ): ZIO[R, Nothing, Cache[Key, E, Value]] =
    ZIO.environment[R].flatMap { env =>
      type StateType = CacheState[Key, E, Value]
      type MapType   = Map[Key, MapValue[E, Value]]

      def addAndPrune(state: StateType, key: Key, promise: Promise[E, Value]): MapType = {
        val map = state.map
        if (map.size >= capacity) map
        else map.updated(key, MapValue.Pending(promise))
      }

      def recordEntry(
        ref: Ref[StateType],
        now: Instant,
        key: Key,
        entryStats: EntryStats,
        exit: Exit[E, Value]
      ): IO[E, Value] =
        exit match {
          case Exit.Failure(cause) =>
            ref.update(state => state.copy(map = state.map - key)).flatMap(_ => ZIO.halt(cause))
          case Exit.Success(value) =>
            val entry = Entry(entryStats, value)

            if (policy.evict.evict(entry)) ref.update(state => state.copy(map = state.map - key)).as(value)
            else
              ref.update { state =>
                val newNow        = Instant.now()
                val loadTime      = Duration.between(now, newNow)
                val newCacheStats = state.cacheStats.addLoad.addLoadTime(loadTime)
                val newEntryStats = state.entryStats.updatedWith(key) {
                  case None        => None
                  case Some(stats) => Some(stats.addLoad(loadTime))
                }
                val newEntries = state.entries + ((key, value))
                val newMap     = state.map.updated(key, MapValue.Complete(exit))

                var newCacheStats2 = newCacheStats
                val newEntryStats2 = newEntryStats
                var newEntries2    = newEntries
                var newMap2        = newMap

                if (newMap.size > capacity) {
                  val last = newEntries.head

                  val lastKey   = last._1
                  val lastEntry = Entry(state.entryStats(lastKey), last._2)

                  if (key != lastKey && policy.priority.greaterThan(entry, lastEntry)) {
                    // The new entry has more priority than the old one:
                    newCacheStats2 = newCacheStats.addEviction
                    newEntries2 = newEntries - last
                    newMap2 = newMap - lastKey // TODO: Make a test that ensures consistency between data structures
                  } else {
                    newEntries2 = state.entries
                    newMap2 = newMap - key
                  }
                }

                state.copy(
                  cacheStats = newCacheStats2,
                  entryStats = newEntryStats2,
                  entries = newEntries2,
                  map = newMap2
                )
              }.flatMap(_ => ZIO.succeedNow(value))
        }

      ZIO.fiberId.zip(Ref.make[StateType](CacheState.initial(policy.priority.toOrdering))).map {
        case (fiberId, ref) =>
          new Cache[Key, E, Value] {
            val identityFn: IO[E, Value] => IO[E, Value] = v => v

            def trackHit(now: Instant, key: Key): UIO[Any] =
              ref.update { state =>
                state.updateCacheStats(CacheStats.addHit).updateEntryStats(key)(EntryStats.addHit(now))
              }

            val trackMiss: UIO[Any] = ref.update(_.updateCacheStats(CacheStats.addMiss))

            def get(key: Key): IO[E, Value] =
              ZIO.uninterruptibleMask { restore =>
                val now = Instant.now()

                ref
                  .modify[IO[E, Value]] { state =>
                    val entryStats = state.entryStats
                    val map        = state.map

                    map.get(key) match {
                      case Some(MapValue.Pending(promise)) =>
                        (trackHit(now, key) *> restore(promise.await), state)
                      case Some(MapValue.Complete(exit)) =>
                        (trackHit(now, key) *> IO.done(exit), state)
                      case None =>
                        val promise = Promise.unsafeMake[E, Value](fiberId)

                        val lookupValue: UIO[Exit[E, Value]] =
                          restore(lookup(key)).run
                            .flatMap(exit => promise.done(exit).flatMap(_ => ZIO.succeedNow(exit)))
                            .provide(env)

                        val stats = entryStats.getOrElse(key, EntryStats.make(now, ttl = None))

                        (
                          trackMiss.flatMap(_ =>
                            lookupValue
                              .flatMap(exit => recordEntry(ref, now, key, stats, exit))
                          ),
                          state.copy(
                            map = addAndPrune(state, key, promise),
                            entryStats = entryStats + (key -> stats)
                          )
                        )
                    }
                  }
                  .flatMap(identityFn)
              }

            def contains(k: Key): UIO[Boolean] = ref.get.map(_.map.contains(k))

            def size: UIO[Int] = ref.get.map(_.map.size)

            def cacheStats: UIO[CacheStats] = ref.get.map(_.cacheStats)

            def entryStats(key: Key): UIO[Option[EntryStats]] = ref.get.map(_.entryStats.get(key))
          }
      }
    }

  import scala.collection.immutable.SortedSet

  private sealed trait MapValue[+Error, +Value]
  private object MapValue {
    final case class Pending[Error, Value](promise: Promise[Error, Value]) extends MapValue[Error, Value]
    final case class Complete[+Error, +Value](exit: Exit[Error, Value])    extends MapValue[Error, Value]
  }

  private case class CacheState[Key, +Error, Value](
    cacheStats: CacheStats,
    entryStats: Map[Key, EntryStats],
    entries: SortedSet[(Key, Value)],
    map: Map[Key, MapValue[Error, Value]]
  ) {
    def updateCacheStats(f: CacheStats => CacheStats): CacheState[Key, Error, Value] =
      copy(cacheStats = f(cacheStats))

    def updateEntryStats(key: Key)(f: EntryStats => EntryStats): CacheState[Key, Error, Value] =
      copy(
        entryStats = entryStats.updatedWith(key) {
          case None        => None
          case Some(stats) => Some(f(stats))
        }
      )
  }
  private object CacheState {
    def initial[Key, E, Value](implicit ordering: Ordering[Entry[Value]]): CacheState[Key, E, Value] = {
      val entryStats: Map[Key, EntryStats] =
        Map.empty
      implicit val entryOrdering: Ordering[(Key, Value)] =
        Ordering.by {
          case (key, value) => entryStats.get(key).map(Entry(_, value))
        }

      CacheState(CacheStats.initial, entryStats, SortedSet.empty[(Key, Value)], Map())
    }
  }
}
