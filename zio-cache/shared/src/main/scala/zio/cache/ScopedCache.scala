package zio.cache

import zio._
import zio.internal.MutableConcurrentQueue

import java.time.{Clock, Duration, Instant}
import java.util
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, LongAdder}
import scala.jdk.CollectionConverters._

sealed abstract class ScopedCache[-Key, +Error, +Value] {

  /**
   * Returns statistics for this cache.
   * @return
   */
  def cacheStats: UIO[CacheStats]

  /**
   * Return whether a resource associated with the specified key exists in the cache.
   * Sometime `contains` can return true if the resource is currently being created
   * but not yet totally created
   * @param key
   * @return
   */
  def contains(key: Key): UIO[Boolean]

  /**
   * Return statistics for the specified entry.
   */
  def entryStats(key: Key): UIO[Option[EntryStats]]

  /**
   * Gets the value from the cache if it exists or otherwise computes it, the release action signals to the cache that
   * the value is no longer being used and can potentially be finalized subject to the policies of the cache
   * @param key
   * @return
   */
  def get(key: Key): ZIO[Scope, Error, Value]

  /**
   * Force the reuse of the lookup function to compute the returned scoped effect associated with the specified key immediately
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
object ScopedCache {

  /**
   * Constructs a new cache with the specified capacity, time to live, and
   * lookup function.
   */
  def make[Key, Environment, Error, Value](
    capacity: Int,
    timeToLive: Duration,
    lookup: ScopedLookup[Key, Environment, Error, Value]
  ): ZIO[Environment with Scope, Nothing, ScopedCache[Key, Error, Value]] =
    makeWith(capacity, lookup)(_ => timeToLive)

  /**
   * Constructs a new cache with the specified capacity, time to live, and
   * lookup function, where the time to live can depend on the `Exit` value
   * returned by the lookup function.
   */
  def makeWith[Key, Environment, Error, Value](
    capacity: Int,
    scopedLookup: ScopedLookup[Key, Environment, Error, Value]
  )(timeToLive: Exit[Error, Value] => Duration): ZIO[Environment with Scope, Nothing, ScopedCache[Key, Error, Value]] =
    makeWith(capacity, scopedLookup, Clock.systemUTC())(timeToLive)

  //util for test because it allow to inject a mocked Clock
  private[cache] def makeWith[Key, Environment, Error, Value](
    capacity: Int,
    scopedLookup: ScopedLookup[Key, Environment, Error, Value],
    clock: Clock
  )(timeToLive: Exit[Error, Value] => Duration): ZIO[Environment with Scope, Nothing, ScopedCache[Key, Error, Value]] =
    ZIO.acquireRelease(buildWith(capacity, scopedLookup, clock)(timeToLive))(_.invalidateAll)

  private def buildWith[Key, Environment, Error, Value](
    capacity: Int,
    scopedLookup: ScopedLookup[Key, Environment, Error, Value],
    clock: Clock
  )(timeToLive: Exit[Error, Value] => Duration): URIO[Environment, ScopedCache[Key, Error, Value]] =
    ZIO.environment[Environment].map { environment =>
      val cacheState = CacheState.initial[Key, Error, Value]()
      import cacheState._

      def trackAccess(key: MapKey[Key]): Array[MapValue[Key, Error, Value]] = {
        val cleanedKey = scala.collection.mutable.ArrayBuilder.make[MapValue[Key, Error, Value]]
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
              val removed = map.remove(key.value)
              if (removed ne null) {
                size -= 1
                cleanedKey += removed
                loop = size > capacity
              }
            } else {
              loop = false
            }
          }
          updating.set(false)
        }
        cleanedKey.result()
      }

      def trackHit(): Unit =
        hits.increment()

      def trackMiss(): Unit =
        misses.increment()

      new ScopedCache[Key, Error, Value] {
        private def ensureMapSizeNotExceeded(key: MapKey[Key]): UIO[Unit] =
          ZIO.foreachParDiscard(trackAccess(key)) { cleanedMapValue =>
            cleanMapValue(cleanedMapValue)
          }

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
                case MapValue.Complete(_, _, _, entryState, _) =>
                  Option(EntryStats(entryState.loaded))
                case MapValue.Refreshing(_, MapValue.Complete(_, _, _, entryState, _)) =>
                  Option(EntryStats(entryState.loaded))
              }
            }
          }

        override def get(k: Key): ZIO[Scope, Error, Value] =
          lookupValueOf(k).memoize.flatMap { lookupValue =>
            ZIO.suspendSucceed {
              var key: MapKey[Key] = null
              var value            = map.get(k)
              if (value eq null) {
                key = new MapKey(k)
                value = map.putIfAbsent(k, MapValue.Pending(key, lookupValue))
              }
              if (value eq null) {
                trackMiss()
                ensureMapSizeNotExceeded(key) *> lookupValue
              } else {
                value match {
                  case MapValue.Pending(key, scoped) =>
                    trackHit()
                    ensureMapSizeNotExceeded(key) *> scoped
                  case complete @ MapValue.Complete(key, _, _, _, timeToLive) =>
                    trackHit()
                    if (hasExpired(timeToLive)) {
                      map.remove(k, value)
                      ensureMapSizeNotExceeded(key) *> complete.releaseOwner *> ZIO.succeed(get(k))
                    } else {
                      ensureMapSizeNotExceeded(key).as(complete.toScoped)
                    }
                  case MapValue.Refreshing(promiseInProgress, complete @ MapValue.Complete(mapKey, _, _, _, ttl)) =>
                    trackHit()
                    if (hasExpired(ttl)) {
                      ensureMapSizeNotExceeded(mapKey) *> promiseInProgress
                    } else {
                      ensureMapSizeNotExceeded(mapKey).as(complete.toScoped)
                    }
                }
              }
            }
          }.flatten

        override def refresh(k: Key): IO[Error, Unit] = lookupValueOf(k).memoize.flatMap { scoped =>
          var value               = map.get(k)
          var newKey: MapKey[Key] = null
          if (value eq null) {
            newKey = new MapKey[Key](k)
            value = map.putIfAbsent(k, MapValue.Pending(newKey, scoped))
          }
          val finalScoped = if (value eq null) {
            ensureMapSizeNotExceeded(newKey) *> scoped
          } else {
            value match {
              case MapValue.Pending(_, scopedEffect) =>
                scopedEffect
              case completeResult @ MapValue.Complete(_, _, _, _, ttl) =>
                if (hasExpired(ttl)) {
                  ZIO.succeed(get(k))
                } else {
                  if (map.replace(k, completeResult, MapValue.Refreshing(scoped, completeResult))) {
                    scoped
                  } else {
                    ZIO.succeed(get(k))
                  }
                }
              case MapValue.Refreshing(scoped, _) => scoped
            }
          }
          finalScoped.flatMap(s => ZIO.scoped(s.unit))
        }

        override def invalidate(k: Key): UIO[Unit] = ZIO.suspendSucceed {
          map.remove(k) match {
            case complete @ MapValue.Complete(_, _, _, _, _) => complete.releaseOwner
            case MapValue.Refreshing(_, complete)            => complete.releaseOwner
            case _                                           => ZIO.unit
          }
        }

        override def invalidateAll: UIO[Unit] =
          ZIO.foreachParDiscard(map.keySet().asScala)(invalidate)

        override def size: UIO[Int] =
          ZIO.succeed(map.size)

        private def cleanMapValue(mapValue: MapValue[Key, Error, Value]): UIO[Unit] =
          mapValue match {
            case complete @ MapValue.Complete(_, _, _, _, _) => complete.releaseOwner
            case MapValue.Refreshing(_, complete)            => complete.releaseOwner
            case _                                           => ZIO.unit
          }

        private def lookupValueOf(key: Key): UIO[ZIO[Scope, Error, Value]] = for {
          scopedEffect <- (for {
                            scope <- Scope.make
                            exit <- scopedLookup(key)
                                      .provideEnvironment(environment.add[Scope](scope))
                                      .exit
                          } yield (exit, scope.close(_)))
                            .onInterrupt(ZIO.succeed(map.remove(key)))
                            .flatMap { case (exit, release) =>
                              val now       = Instant.now(clock)
                              val expiredAt = now.plus(timeToLive(exit))
                              exit match {
                                case Exit.Success(value) =>
                                  val exitWithReleaser: Exit[Nothing, (Value, Finalizer)] =
                                    Exit.succeed(value -> release)
                                  val completedResult = MapValue
                                    .Complete(
                                      key = new MapKey(key),
                                      exit = exitWithReleaser,
                                      ownerCount = new AtomicInteger(1),
                                      entryStats = EntryStats(now),
                                      timeToLive = expiredAt
                                    )
                                  val previousValue = map.put(key, completedResult)
                                  ZIO.succeed(
                                    cleanMapValue(previousValue).as(completedResult.toScoped).flatten
                                  )
                                case failure @ Exit.Failure(_) =>
                                  val completedResult =
                                    MapValue.Complete(
                                      key = new MapKey(key),
                                      exit = failure,
                                      ownerCount = new AtomicInteger(0),
                                      entryStats = EntryStats(now),
                                      timeToLive = expiredAt
                                    )
                                  val previousValue = map.put(key, completedResult)
                                  release(failure) *> ZIO.succeed(
                                    cleanMapValue(previousValue).as(completedResult.toScoped).flatten
                                  )
                              }
                            }
                            .memoize
        } yield scopedEffect.flatten

        private def hasExpired(timeToLive: Instant) =
          Instant.now(clock).isAfter(timeToLive)
      }
    }

  /**
   * A `MapValue` represents a value in the cache. A value may either be
   * `Pending` with a `Promise` that will contain the result of computing the
   * lookup function, when it is available, or `Complete` with an `Exit` value
   * that contains the result of computing the lookup function.
   */
  private sealed trait MapValue[Key, +Error, +Value] extends Product with Serializable

  private object MapValue {
    final case class Pending[Key, Error, Value](
      key: MapKey[Key],
      scoped: UIO[ZIO[Scope, Error, Value]]
    ) extends MapValue[Key, Error, Value]

    final case class Complete[Key, +Error, +Value](
      key: MapKey[Key],
      exit: Exit[Error, (Value, Finalizer)],
      ownerCount: AtomicInteger,
      entryStats: EntryStats,
      timeToLive: Instant
    ) extends MapValue[Key, Error, Value] {
      def toScoped: ZIO[Scope, Error, Value] =
        exit.foldExit(
          cause => ZIO.done(Exit.Failure(cause)),
          { case (value, _) =>
            ZIO.acquireRelease(ZIO.succeed(ownerCount.incrementAndGet()).as(value)) { _ =>
              releaseOwner
            }
          }
        )

      def releaseOwner: UIO[Unit] =
        exit.foldExit(
          _ => ZIO.unit,
          { case (_, finalizer) =>
            ZIO.succeed(ownerCount.decrementAndGet()).flatMap { numOwner =>
              finalizer(Exit.unit).when(numOwner == 0).unit
            }
          }
        )
    }

    final case class Refreshing[Key, Error, Value](
      scopedEffect: UIO[ZIO[Scope, Error, Value]],
      complete: Complete[Key, Error, Value]
    ) extends MapValue[Key, Error, Value]
  }

  /**
   * The `CacheState` represents the mutable state underlying the cache.
   */
  private final case class CacheState[Key, Error, Value](
    map: util.Map[Key, MapValue[Key, Error, Value]],
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

  type Finalizer = (=> Exit[Any, Any]) => UIO[Any]
}
