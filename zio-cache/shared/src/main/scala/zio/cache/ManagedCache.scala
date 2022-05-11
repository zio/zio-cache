package zio.cache

import zio.internal.MutableConcurrentQueue
import zio.{Exit, IO, Managed, Promise, TaskManaged, UIO, URIO, URManaged, ZIO}

import java.time.{Duration, Instant}
import java.util.Map
import java.util.concurrent.atomic.{AtomicBoolean, LongAdder}

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
  )(timeToLive: Exit[Error, Value] => Duration): URManaged[Environment, ManagedCache[Key, Error, Value]] =
    ZIO.environment[Environment].flatMap { environment =>
      ZIO.fiberId.map { fiberId =>
        val cacheState = CacheState.initial[Key, Error, Value]()
        import cacheState._

        def trackAccess(key: MapKey[Key]): Unit = {
          accesses.offer(key)
          if (updating.compareAndSet(false, true)) {
            var loop = true
            while (loop) {
              val key = accesses.poll(null)
              if (key ne null) {
                keys.add(key)
              } else {
                loop = false
              }
            }
            var size = map.size
            loop = size > capacity
            while (loop) {
              val key = keys.remove()
              if (key ne null) {
                if (map.remove(key.value) ne null) {
                  size -= 1
                  loop = size > capacity
                }
              } else {
                loop = false
              }
            }
            updating.set(false)
          }
        }

        def trackHit(): Unit =
          hits.increment()

        def trackMiss(): Unit =
          misses.increment()

        new Cache[Key, Error, Value] {

          override def cacheStats: UIO[CacheStats] =
            ZIO.succeed(CacheStats(hits.longValue, misses.longValue, map.size))

          override def contains(k: Key): UIO[Boolean] =
            ZIO.succeed(map.containsKey(k))

          override def entryStats(k: Key): UIO[Option[EntryStats]] =
            ZIO.succeed {
              val value = map.get(k)
              if (value eq null) None
              else {
                value match {
                  case MapValue.Pending(_, _) =>
                    None
                  case MapValue.Complete(_, _, entryState, _) =>
                    Option(EntryStats(entryState.loaded))
                  case MapValue.Refreshing(_, MapValue.Complete(_, _, entryState, _)) =>
                    Option(EntryStats(entryState.loaded))
                }
              }
            }

          override def get(k: Key): IO[Error, Value] =
            ZIO.effectSuspendTotal {
              var key: MapKey[Key]               = null
              var promise: Promise[Error, Value] = null
              var value                          = map.get(k)
              if (value eq null) {
                promise = newPromise()
                key = new MapKey(k)
                value = map.putIfAbsent(k, MapValue.Pending(key, promise))
              }
              if (value eq null) {
                trackAccess(key)
                trackMiss()
                lookupValueOf(k, promise)
              } else {
                value match {
                  case MapValue.Pending(key, promise) =>
                    trackAccess(key)
                    trackHit()
                    promise.await
                  case MapValue.Complete(key, exit, _, timeToLive) =>
                    trackAccess(key)
                    trackHit()
                    if (hasExpired(timeToLive)) {
                      map.remove(k, value)
                      get(k)
                    } else {
                      ZIO.done(exit)
                    }
                  case MapValue.Refreshing(
                        promiseInProgress,
                        MapValue.Complete(mapKey, currentResult, _, ttl)
                      ) =>
                    trackAccess(mapKey)
                    trackHit()
                    if (hasExpired(ttl)) {
                      promiseInProgress.await
                    } else {
                      ZIO.done(currentResult)
                    }
                }
              }
            }

          override def refresh(k: Key): IO[Error, Unit] =
            ZIO.effectSuspendTotal {
              val promise = newPromise()
              var value   = map.get(k)
              if (value eq null) {
                value = map.putIfAbsent(k, MapValue.Pending(new MapKey(k), promise))
              }
              val result = if (value eq null) {
                lookupValueOf(k, promise)
              } else {
                value match {
                  case MapValue.Pending(_, promiseInProgress) =>
                    promiseInProgress.await
                  case completedResult @ MapValue.Complete(mapKey, _, _, ttl) =>
                    if (hasExpired(ttl)) {
                      map.remove(k, value)
                      get(k)
                    } else {
                      // Only trigger the lookup if we're still the current value, `completedResult`
                      lookupValueOf(mapKey.value, promise).when {
                        map.replace(k, completedResult, MapValue.Refreshing(promise, completedResult))
                      }
                    }
                  case MapValue.Refreshing(promiseInProgress, _) =>
                    promiseInProgress.await
                }
              }
              result.unit
            }

          override def invalidate(k: Key): UIO[Unit] =
            ZIO.succeed {
              map.remove(k)
              ()
            }

          override def invalidateAll: UIO[Unit] =
            ZIO.succeed {
              map.clear()
            }

          override def size: UIO[Int] =
            ZIO.succeed(map.size)

          private def lookupValueOf(key: Key, promise: Promise[Error, Value]): IO[Error, Value] =
            lookup(key)
              .provide(environment)
              .run
              .flatMap { lookupResult =>
                val now = Instant.now()
                val completedResult = MapValue.Complete(
                  new MapKey(key),
                  lookupResult,
                  EntryStats(now),
                  now.plus(timeToLive(lookupResult))
                )

                map.put(key, completedResult)
                promise.done(lookupResult) *>
                  ZIO.done(lookupResult)
              }
              .onInterrupt(
                promise.interrupt.as(map.remove(key))
              )

          private def newPromise() =
            Promise.unsafeMake[Error, Value](fiberId)

          private def hasExpired(timeToLive: Instant) =
            Instant.now().isAfter(timeToLive)
        }
      }
    }

  /**
   * A `MapValue` represents a value in the cache. A value may either be
   * `Pending` with a `Promise` that will contain the result of computing the
   * lookup function, when it is available, or `Complete` with an `Exit` value
   * that contains the result of computing the lookup function.
   */
  private sealed trait MapValue[Key, Error, Value] extends Product with Serializable

  private object MapValue {
    final case class Pending[Key, Error, Value](
      key: MapKey[Key],
      promise: Promise[Error, Value]
    ) extends MapValue[Key, Error, Value]

    final case class Complete[Key, Error, Value](
      key: MapKey[Key],
      exit: Exit[Error, Value],
      entryStats: EntryStats,
      timeToLive: Instant
    ) extends MapValue[Key, Error, Value]

    final case class Refreshing[Key, Error, Value](
      promise: Promise[Error, Value],
      complete: Complete[Key, Error, Value]
    ) extends MapValue[Key, Error, Value]
  }

  /**
   * The `CacheState` represents the mutable state underlying the cache.
   */
  private final case class CacheState[Key, Error, Value](
    map: Map[Key, MapValue[Key, Error, Value]],
    keys: KeySet[Key],
    accesses: MutableConcurrentQueue[MapKey[Key]],
    hits: LongAdder,
    misses: LongAdder,
    updating: AtomicBoolean
  )

  private object CacheState {

    /**
     * Constructs an initial cache state.
     */
    def initial[Key, Error, Value](): CacheState[Key, Error, Value] =
      CacheState(
        Platform.newConcurrentMap,
        new KeySet,
        MutableConcurrentQueue.unbounded,
        new LongAdder,
        new LongAdder,
        new AtomicBoolean(false)
      )
  }
}
