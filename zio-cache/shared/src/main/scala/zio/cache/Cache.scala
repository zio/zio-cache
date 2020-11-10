package zio.cache

import java.time.Instant

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
}

/*

Benchmarks:

 Parameters: size of the cache

 * Fill benchmark: time to fill it up from empty state
 * `get` times: how long does it take to get something out of the cache?
 * Case 1: Cache is populated with value being retrieved
 * Case 2: Cache is NOT populated with value being retrieved
     Baseline: 600k unpopulated gets/second
 * Frequent eviction
 * Least-recently used / accessed (high churn)

 */
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
      type MapType   = Map[Key, MapEntry[E, Value]]

      val _ = policy

      // def evictExpiredEntries(now: Instant, map: MapType): MapType =
      //   map.filter { case (key, value) => policy.evict.evict(now, ???) }

      // def toEntry(value: Value): Entry[Value] = ???

      def addAndPrune(now: Instant, state: StateType, key: Key, promise: Promise[E, Value]): MapType = {
        val map        = state.map
        val entryStats = EntryStats.make(now)

        if (map.size >= capacity) map
        else map.updated(key, MapEntry(entryStats, MapValue.Pending(promise)))
      }

      def recordEntry(
        ref: Ref[StateType],
        now: Instant,
        key: Key,
        entryStats: EntryStats,
        exit: Exit[E, Value]
      ): IO[E, Value] =
        exit match {
          case Exit.Failure(cause) => ZIO.halt(cause) // TODO: Remove the promise from the map, if it exists in the map
          case Exit.Success(value) =>
            val entry = Entry(entryStats, value)

            if (policy.evict.evict(now, entry)) ref.update(state => state.copy(map = state.map - key)).as(value)
            else
              ref.update { state =>
                val newEntries = state.entries + ((key, entry))
                val newMap     = state.map.updated(key, MapEntry(entryStats, MapValue.Complete(exit)))

                var newEntries2 = newEntries
                var newMap2     = newMap

                if (newMap.size > capacity) {
                  if (newEntries.nonEmpty) {
                    val last = newEntries.head

                    val lastKey   = last._1
                    val lastEntry = last._2

                    if (key != lastKey && policy.priority.greaterThan(entry, lastEntry)) {
                      // The new entry has more priority than the old one:
                      newEntries2 = newEntries - last
                      newMap2 = newMap - lastKey // TODO: Make a test that ensures consistency between data structures
                    } else {
                      newEntries2 = state.entries
                      newMap2 = newMap - key
                    }
                  } else {
                    newEntries2 = state.entries
                    newMap2 = newMap - key // TODO: What if map is filled with incomplete promises???
                  }
                }

                state.copy(entries = newEntries2, map = newMap2)
              }.flatMap(_ => ZIO.succeedNow(value))
        }

      // 1. Do NOT store failed promises inside the map
      //    Instead: handle "delay failures" using Lookup

      ZIO.fiberId.zip(Ref.make[StateType](CacheState.initial(policy.priority.toOrdering))).map {
        case (fiberId, ref) =>
          new Cache[Key, E, Value] {
            val identityFn: IO[E, Value] => IO[E, Value] = v => v

            def trackHit(key: Key): UIO[Any] =
              ref.update { state =>
                state.updateCacheStats(CacheStats.addHit).updateEntryStats(key)(EntryStats.addHit)
              }

            val trackMiss: UIO[Any] = ref.update(_.updateCacheStats(CacheStats.addMiss))

            def get(key: Key): IO[E, Value] =
              ZIO.uninterruptibleMask { restore =>
                ref
                  .modify[IO[E, Value]] { state =>
                    val map = state.map

                    map.get(key) match {
                      case Some(MapEntry(_, MapValue.Pending(promise))) =>
                        (trackHit(key) *> restore(promise.await), state)
                      case Some(MapEntry(_, MapValue.Complete(exit))) => (trackHit(key) *> IO.done(exit), state)
                      case None =>
                        val promise = Promise.unsafeMake[E, Value](fiberId)
                        val now     = Instant.now()

                        // provide decreases churn performance by 15 ops/second
                        val lookupValue: UIO[Exit[E, Value]] =
                          restore(lookup(key)).run
                            .flatMap(exit => promise.done(exit).flatMap(_ => ZIO.succeedNow(exit)))
                            .provide(env)

                        (
                          trackMiss.flatMap(_ =>
                            lookupValue
                              .flatMap(exit => recordEntry(ref, now, key, EntryStats.make(now), exit))
                          ),
                          state.copy(map = addAndPrune(now, state, key, promise))
                        )
                    }
                  }
                  .flatMap(identityFn)
              }

            def contains(k: Key): UIO[Boolean] = ref.get.map(_.map.contains(k))

            def size: UIO[Int] = ref.get.map(_.map.size)
          }
      }
    }

  import scala.collection.immutable.SortedSet

  private final case class MapEntry[+Error, +Value](entryStats: EntryStats, mapValue: MapValue[Error, Value])
  private sealed trait MapValue[+Error, +Value]
  private object MapValue {
    final case class Pending[Error, Value](promise: Promise[Error, Value]) extends MapValue[Error, Value]
    final case class Complete[+Error, +Value](exit: Exit[Error, Value])    extends MapValue[Error, Value]
  }

  private case class CacheState[Key, +Error, Value](
    cacheStats: CacheStats,
    entries: SortedSet[(Key, Entry[Value])],
    map: Map[Key, MapEntry[Error, Value]]
  ) {
    def updateCacheStats(f: CacheStats => CacheStats): CacheState[Key, Error, Value] =
      copy(cacheStats = f(cacheStats))

    def updateEntryStats(key: Key)(f: EntryStats => EntryStats): CacheState[Key, Error, Value] =
      copy(map = map.updatedWith(key) {
        case None                     => None
        case Some(MapEntry(stats, v)) => Some(MapEntry(f(stats), v))
      })
  }
  private object CacheState {
    def initial[Key, E, Value](implicit ordering: Ordering[Entry[Value]]): CacheState[Key, E, Value] = {
      implicit val tupleOrdering: Ordering[(Key, Entry[Value])] =
        Ordering.by[(Key, Entry[Value]), Entry[Value]](_._2)

      CacheState(CacheStats.initial, SortedSet.empty[(Key, Entry[Value])], Map())
    }
  }
}
