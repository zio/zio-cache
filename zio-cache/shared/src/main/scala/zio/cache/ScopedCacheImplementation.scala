package zio.cache

import zio.cache.ScopedCache.Finalizer
import zio.cache.ScopedCacheImplementation.{CacheState, MapValue}
import zio.internal.MutableConcurrentQueue
import zio.{Clock, Exit, IO, Scope, UIO, Unsafe, ZEnvironment, ZIO}

import java.time.{Duration, Instant}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, LongAdder}
import scala.jdk.CollectionConverters._

private final class ScopedCacheImplementation[Key, Environment, Error, Value](
  capacity: Int,
  scopedLookup: ScopedLookup[Key, Environment, Error, Value],
  timeToLive: Exit[Error, Value] => Duration,
  clock: Clock,
  environment: ZEnvironment[Environment]
) extends ScopedCache[Key, Error, Value] {
  private val cacheState = CacheState.initial[Key, Error, Value]()
  import cacheState._

  private def trackAccess(key: MapKey[Key]): Array[MapValue[Key, Error, Value]] = {
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
      // Keys whose entry is gone or has a newer key stay in the set until they are dropped here
      val tooManyStaleKeys = keys.size > 2 * size
      if (tooManyStaleKeys) keys.retain(isLive)
      loop = size > capacity
      while (loop) {
        val key = keys.remove()
        if (key ne null) {
          val removed = removeEntryOf(key)
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

  /**
   * Removes the entry tracked by `key`, if the map still holds it, and returns
   * the removed entry, or `null` if there was none.
   */
  private def removeEntryOf(key: MapKey[Key]): MapValue[Key, Error, Value] = {
    def changedBeforeRemove(value: MapValue[Key, Error, Value]): Boolean =
      isEntryOf(key, value) && !map.remove(key.value, value)

    var value = map.get(key.value)
    // The entry can change state (e.g. `Pending` to `Complete`) while keeping its key, so the remove is retried
    while (changedBeforeRemove(value)) value = map.get(key.value)
    if (isEntryOf(key, value)) value else null
  }

  private def isLive(key: MapKey[Key]): Boolean =
    isEntryOf(key, map.get(key.value))

  private def isEntryOf(key: MapKey[Key], value: MapValue[Key, Error, Value]): Boolean =
    (value ne null) && (value.key eq key)

  private def trackHit(): Unit =
    hits.increment()

  private def trackMiss(): Unit =
    misses.increment()

  private def ensureMapSizeNotExceeded(key: MapKey[Key]): UIO[Unit] =
    ZIO.foreachParDiscard(trackAccess(key)) { cleanedMapValue =>
      cleanMapValue(cleanedMapValue)
    }

  override def cacheStats: UIO[CacheStats] =
    ZIO.succeed(CacheStats(hits.longValue, misses.longValue, map.size))

  override def contains(k: Key): UIO[Boolean] =
    ZIO.succeed {
      map.get(k) match {
        case null                                                           => false
        case _: MapValue.Pending[?, ?, ?] | _: MapValue.Refreshing[?, ?, ?] => true
        case MapValue.Complete(_, _, _, _, timeToLive)                      => !hasExpired(timeToLive)
      }
    }

  override def entryStats(k: Key): UIO[Option[EntryStats]] =
    ZIO.succeed {
      map.get(k) match {
        case null | _: MapValue.Pending[?, ?, ?]                               => None
        case MapValue.Complete(_, _, _, entryState, _)                         => Option(EntryStats(entryState.loaded))
        case MapValue.Refreshing(_, MapValue.Complete(_, _, _, entryState, _)) => Option(EntryStats(entryState.loaded))
      }
    }

  def freeExpired: UIO[Int] = ZIO.suspendSucceedUnsafe { implicit unsafe =>
    var expiredKey = List.empty[Key]
    map.entrySet().forEach { entry =>
      entry.getValue match {
        case MapValue.Complete(_, _, _, _, ttl) if hasExpired(ttl) =>
          expiredKey = entry.getKey :: expiredKey
        case _ =>
          ()
      }
    }

    ZIO
      .foreachDiscard(expiredKey)(invalidate)
      .as(expiredKey.length)
  }

  override def get(k: Key): ZIO[Scope, Error, Value] =
    ZIO.uninterruptibleMask { implicit restore =>
      val newKey = new MapKey(k)
      lookupValueOf(k, newKey).memoize.flatMap { lookupValue =>
        var value = map.get(k)
        if (value eq null) {
          value = map.putIfAbsent(k, MapValue.Pending(newKey, lookupValue))
        }
        value match {
          case null =>
            trackMiss()
            ensureMapSizeNotExceeded(newKey) *> lookupValue
          case MapValue.Pending(key, scoped) =>
            trackHit()
            ensureMapSizeNotExceeded(key) *> scoped
          case complete @ MapValue.Complete(key, _, _, _, timeToLive) =>
            if (hasExpired(timeToLive)) {
              map.remove(k, value)
              ensureMapSizeNotExceeded(key) *> complete.releaseOwner.as(get(k))
            } else {
              trackHit()
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
      }.flatMap(restore(_))
    }

  override def refresh(k: Key): IO[Error, Unit] =
    ZIO.uninterruptibleMask { implicit restore =>
      val current = map.get(k)
      // A refresh keeps the key of the entry it replaces
      val newKey = current match {
        case MapValue.Complete(key, _, _, _, _)                                    => key
        case null | _: MapValue.Pending[?, ?, ?] | _: MapValue.Refreshing[?, ?, ?] => new MapKey[Key](k)
      }
      lookupValueOf(k, newKey).memoize.flatMap { scoped =>
        var value = current
        if (value eq null) {
          value = map.putIfAbsent(k, MapValue.Pending(newKey, scoped))
        }
        val finalScoped = value match {
          case null =>
            ensureMapSizeNotExceeded(newKey) *> scoped
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
        finalScoped.flatMap(s => restore(ZIO.scoped(s.unit)))
      }
    }

  override def invalidate(k: Key): UIO[Unit] = ZIO.suspendSucceed {
    map.remove(k) match {
      case complete @ MapValue.Complete(_, _, _, _, _) => complete.releaseOwner
      case MapValue.Refreshing(_, complete)            => complete.releaseOwner
      case _                                           => Exit.unit
    }
  }

  override def invalidateAll: UIO[Unit] =
    ZIO.foreachParDiscard(map.keySet().asScala)(invalidate)

  override def size: UIO[Int] =
    ZIO.succeed(map.size)

  private[cache] def keyCount: Int =
    keys.size

  private def cleanMapValue(mapValue: MapValue[Key, Error, Value]): UIO[Unit] =
    mapValue match {
      case complete @ MapValue.Complete(_, _, _, _, _) => complete.releaseOwner
      case MapValue.Refreshing(_, complete)            => complete.releaseOwner
      case _                                           => Exit.unit
    }

  private def lookupValueOf(key: Key, mapKey: MapKey[Key])(implicit
    restore: ZIO.InterruptibilityRestorer
  ): UIO[ZIO[Scope, Error, Value]] =
    ZIO.suspendSucceed {
      val scope   = Scope.unsafe.make(Unsafe)
      val release = scope.close(_)
      restore(scopedLookup(key))
        .provideEnvironment(environment.unsafe.addScope(scope)(Unsafe))
        .exitWith {
          case exit @ Exit.Success(value) =>
            val now       = clock.unsafe.instant()(Unsafe)
            val expiredAt = now.plus(timeToLive(exit))
            val exitWithReleaser: Exit[Nothing, (Value, Finalizer)] =
              Exit.succeed(value -> release)
            val completedResult = MapValue
              .Complete(
                key = mapKey,
                exit = exitWithReleaser,
                ownerCount = new AtomicInteger(1),
                entryStats = EntryStats(now),
                timeToLive = expiredAt
              )
            val previousValue = map.put(key, completedResult)
            Exit.succeed(cleanMapValue(previousValue) *> completedResult.toScoped)
          case exit @ Exit.Failure(c) if c.isInterruptedOnly =>
            map.remove(key)
            Exit.succeed(exit)
          case exit: Exit.Failure[Error] =>
            val now       = clock.unsafe.instant()(Unsafe)
            val expiredAt = now.plus(timeToLive(exit))
            val completedResult =
              MapValue.Complete(
                key = mapKey,
                exit = exit,
                ownerCount = new AtomicInteger(0),
                entryStats = EntryStats(now),
                timeToLive = expiredAt
              )
            val previousValue = map.put(key, completedResult)
            release(exit).as(cleanMapValue(previousValue) *> completedResult.toScoped)
        }
    }

  private def hasExpired(timeToLive: Instant) =
    clock.unsafe.instant()(Unsafe).isAfter(timeToLive)
}

object ScopedCacheImplementation {
  private object CacheState {

    /**
     * Constructs an initial cache state.
     */
    def initial[Key, Error, Value](): CacheState[Key, Error, Value] =
      CacheState(
        new ConcurrentHashMap(),
        new KeySet,
        MutableConcurrentQueue.unbounded,
        new LongAdder,
        new LongAdder,
        new AtomicBoolean(false)
      )
  }

  /**
   * A `MapValue` represents a value in the cache. A value may either be
   * `Pending` with a `Promise` that will contain the result of computing the
   * lookup function, when it is available, or `Complete` with an `Exit` value
   * that contains the result of computing the lookup function.
   */
  private sealed trait MapValue[Key, +Error, +Value] extends Product with Serializable {
    def key: MapKey[Key]
  }

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
          cause => Exit.failCause(cause),
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
              finalizer(Exit.unit).whenDiscard(numOwner == 0)
            }
          }
        )
    }

    final case class Refreshing[Key, Error, Value](
      scopedEffect: UIO[ZIO[Scope, Error, Value]],
      complete: Complete[Key, Error, Value]
    ) extends MapValue[Key, Error, Value] {
      def key: MapKey[Key] = complete.key
    }
  }

  /**
   * The `CacheState` represents the mutable state underlying the cache.
   */
  private final case class CacheState[Key, Error, Value](
    map: ConcurrentHashMap[Key, MapValue[Key, Error, Value]],
    keys: KeySet[Key],
    accesses: MutableConcurrentQueue[MapKey[Key]],
    hits: LongAdder,
    misses: LongAdder,
    updating: AtomicBoolean
  )
}
