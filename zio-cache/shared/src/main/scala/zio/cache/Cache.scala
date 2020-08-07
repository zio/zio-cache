package zio.cache

import zio.{ IO, ZIO }

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
    ZIO.environment[R].map { env =>
      val _ = capacity
      val _ = policy

      new Cache[Key, E, Value] {
        def get(key: Key): IO[E, Value] = lookup(key).provide(env)
      }
    }
}
