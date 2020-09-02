package zio.cache

import java.time.Instant

import zio.{ IO, Promise, Ref, UIO, ZIO }

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
      type MapType = Map[Key, (EntryStats, Promise[E, Value])]

      val _ = policy

      // def evictExpiredEntries(now: Instant, map: MapType): MapType =
      //   map.filter { case (key, value) => policy.evict.evict(now, ???) }

      // def toEntry(value: Value): Entry[Value] = ???

      def addAndPrune(now: Instant, map: MapType, key: Key, promise: Promise[E, Value]): MapType = {
        val entryStats = EntryStats.make(now)

        if (map.size >= capacity) map else map.updated(key, entryStats -> promise)
      }

      // 1. Do NOT store failed promises inside the map
      //    Instead: handle "delay failures" using Lookup

      Ref.make[MapType](Map()).map { ref =>
        new Cache[Key, E, Value] {
          def get(key: Key): IO[E, Value] =
            ZIO.uninterruptibleMask { restore =>
              for {
                promise <- Promise.make[E, Value]
                await <- ref.modify[IO[E, Value]] { map =>
                          map.get(key) match {
                            case Some((_, promise)) => (restore(promise.await), map)
                            case None =>
                              val now = Instant.now()

                              val lookupValue = restore(lookup(key)).to(promise).provide(env)

                              (lookupValue *> promise.await, addAndPrune(now, map, key, promise))
                          }
                        }
                value <- await
              } yield value
            }

          def contains(k: Key): UIO[Boolean] = ref.get.map(_.contains(k))

          def size: UIO[Int] = ref.get.map(_.size)
        }
      }
    }
}
