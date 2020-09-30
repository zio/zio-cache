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
        exit.fold(
          cause => ZIO.halt(cause), // TODO: Remove the promise from the map, if it exists in the map
          value => {
            val entry = Entry(entryStats, value)

            if (policy.evict.evict(now, entry)) ref.update(state => state.copy(map = state.map - key)).as(value)
            else
              ref.update {
                state =>
                  // Big oh for the lookup function: O(capacity * (log capacity))
                  val (newEntries, newMap) = {
                    val newEntries = state.entries + (key -> entry)
                    val newMap     = state.map + (key -> MapEntry(entryStats, MapValue.Complete(exit)))

                    if (newMap.size > capacity) {
                      newEntries.lastOption match {
                        case Some((lastKey, lastEntry)) =>
                          if (key != lastKey && policy.priority.compare(lastEntry, entry) == CacheWorth.Right) (newEntries, newMap - lastKey)
                          else (state.entries, newMap - key)

                        case None => (state.entries, newMap - key) // TODO: What if map is filled with incomplete promises???
                      }
                    } else (newEntries, newMap)
                  }

                  state.copy(entries = newEntries, map = newMap)
              }.as(value)
          }
        )

      // 1. Do NOT store failed promises inside the map
      //    Instead: handle "delay failures" using Lookup

      Ref.make[StateType](CacheState.initial(policy.priority.toOrdering)).map { ref =>
        new Cache[Key, E, Value] {
          // Must guarantee: O(1)
          def get(key: Key): IO[E, Value] =
            ZIO.uninterruptibleMask { restore =>
              for {
                promise <- Promise.make[E, Value]
                await <- ref.modify[IO[E, Value]] { state =>
                          val map = state.map

                          map.get(key) match {
                            case Some(MapEntry(_, MapValue.Pending(promise))) => (restore(promise.await), state)
                            case Some(MapEntry(_, MapValue.Complete(exit)))   => (IO.done(exit), state)
                            case None =>
                              val now = Instant.now()

                              val lookupValue: UIO[Exit[E, Value]] =
                                restore(lookup(key)).run.flatMap(exit => promise.done(exit).as(exit)).provide(env)

                              (
                                lookupValue.flatMap(exit => recordEntry(ref, now, key, EntryStats.make(now), exit)),
                                state.copy(map = addAndPrune(now, state, key, promise))
                              )
                          }
                        }
                value <- await
              } yield value
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

  private case class CacheState[Key, +Error, Value](cacheStats: CacheStats, entries: SortedSet[(Key, Entry[Value])], map: Map[Key, MapEntry[Error, Value]])
  private object CacheState {
    def initial[Key, E, Value](implicit ordering: Ordering[Entry[Value]]): CacheState[Key, E, Value] = {
      implicit val tupleOrdering: Ordering[(Key, Entry[Value])] = 
        Ordering.by(_._2)

      CacheState(CacheStats.initial, SortedSet.empty[(Key, Entry[Value])], Map())
    }
  }
}
