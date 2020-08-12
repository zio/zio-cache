package zio.cache

import zio.{ IO, Promise, Ref, ZIO }

// import java.time.Instant

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
      type MapType = Map[Key, Promise[E, Value]]

      val _ = capacity 
      val _ = policy 

      // def evictExpiredEntries(now: Instant, map: MapType): MapType = 
      //   map.filter { case (key, value) => policy.evict.evict(now, ???) }

      // def toEntry(value: Value): Entry[Value] = ???

      def addAndPrune(map: MapType, key: Key, promise: Promise[E, Value]): MapType = 
        if (map.size >= capacity) map else map + (key -> promise)

      // 1. Do NOT store failed promises inside the map 
      //    Instead: handle "delay failures" using Lookup

      Ref.make[MapType](Map()).map { ref =>
        new Cache[Key, E, Value] {
          def get(key: Key): IO[E, Value] = 
            ZIO.uninterruptibleMask { restore =>
              for {
                promise  <- Promise.make[E, Value]
                await    <- ref.modify[IO[E, Value]] { map =>
                              map.get(key) match {
                                case Some(promise) => (restore(promise.await), map)
                                case None => 
                                  val lookupValue = restore(lookup(key)).to(promise).provide(env)

                                  (lookupValue *> restore(promise.await), addAndPrune(map, key, promise))
                              }
                            }
                value    <- await 
              } yield value
            }
        }
      }
    }
}
