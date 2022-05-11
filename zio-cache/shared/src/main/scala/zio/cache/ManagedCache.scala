package zio.cache

import zio.{Exit, IO, Managed, TaskManaged, UIO, URIO, URManaged}

import java.time.Duration

sealed abstract class ManagedCache[-Key, +Error, +Value] {

  /**
   * Returns statistics for this cache.
   * @return
   */
  def cacheStats: UIO[CacheStats]

  /**
   * Return whether a Managed associated with the specified key exists in the cache.
   * @param key
   * @return
   */
  def contains(key: Key): UIO[Boolean]

  /**
   * Return statistics for the specified entry.
   */
  def entryStats(key: Key): UIO[Option[EntryStats]]

  /**
   * Give a proxy managed on the resource already created for this key if any.
   * Otherwise computes the managed with the lookup function, puts it in the cache,
   * and returns it.
   * The given managed is lazy, before it's actually used the resource is not created and
   * cached.
   * The register creation time for the given resource is saved one the first managed used
   * @param key
   * @return
   */
  def get(key: Key): UIO[Managed[Error, Value]]

  /**
   * Force the reuse of the lookup function to compute the returned managed associated with the specified key immediately
   * Once the new resource is recomputed, the old resource associated to the key is cleaned (once all fiber using it are done with it)
   * During the time the new resource is computed, concurrent call the .get will use the old resource if this one is not expired
   * @param key
   * @return
   */
  def refresh(key: Key): IO[Error, Unit]

  /**
   * Invalidates the resource associated with the specified key.
   */
  def invalidate(key: Key): UIO[Unit]

  /**
   * Invalidates all values in the cache.
   */
  def invalidateAll: UIO[Unit]

  /**
   * Returns the approximate number of values in the cache.
   */
  def size: UIO[Int]
}
object ManagedCache {

  /**
   * Constructs a new cache with the specified capacity, time to live, and
   * lookup function.
   */
  def make[Key, Environment, Error, Value](
    capacity: Int,
    timeToLive: Duration,
    lookup: ManagedLookup[Key, Environment, Error, Value]
  ): URManaged[Environment, ManagedCache[Key, Error, Value]] =
    makeWith(capacity, lookup)(_ => timeToLive)

  /**
   * Constructs a new cache with the specified capacity, time to live, and
   * lookup function, where the time to live can depend on the `Exit` value
   * returned by the lookup function.
   */
  def makeWith[Key, Environment, Error, Value](
    capacity: Int,
    managedLookup: ManagedLookup[Key, Environment, Error, Value]
  )(timeToLive: Exit[Error, Value] => Duration): URManaged[Environment, ManagedCache[Key, Error, Value]] = ???
}
