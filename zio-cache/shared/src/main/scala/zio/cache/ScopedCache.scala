/*
 * Copyright 2020-2023 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.cache

import zio._
import zio.internal.MutableConcurrentQueue

import java.time.{Duration, Instant}
import java.util
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, LongAdder}
import scala.jdk.CollectionConverters._

/**
 * A `ScopedCache` is like a [[Cache]] whose values are resources. Its lookup
 * function is a [[ScopedLookup]]: it acquires a resource in a `Scope`, and the
 * cache keeps that resource open while it is cached.
 *
 * `get` acquires the cached resource in the caller's `Scope`. The resource's
 * finalizers run once the cache has dropped the entry (it expired, was
 * evicted, was replaced by `refresh`, was invalidated, or the cache's own scope
 * closed) and no caller's scope still holds it. A lookup that fails releases
 * its resource right away. Expired entries are dropped by a background sweep
 * that runs about once a second, or by a `get` for that key, so an expired
 * resource can stay open for up to about a second after its time to live
 * passes.
 *
 * {{{
 * import zio._
 *
 * val program: UIO[Unit] =
 *   ZIO.scoped {
 *     for {
 *       cache <- ScopedCache.make(
 *                  capacity = 10,
 *                  timeToLive = 1.minute,
 *                  lookup = ScopedLookup { (id: Int) =>
 *                    val open  = Console.printLine(s"open \$id").orDie
 *                    val close = Console.printLine(s"close \$id").orDie
 *                    ZIO.acquireRelease(open.as(id))(_ => close)
 *                  }
 *                )
 *       first  <- ZIO.scoped(cache.get(1))
 *       second <- ZIO.scoped(cache.get(1))
 *     } yield ()
 *   }
 * // prints "open 1" once, then "close 1" when the outer scope closes
 * }}}
 *
 * @see [[https://zio.dev/zio-cache/scoped-cache/ Scoped Cache]]
 */
abstract class ScopedCache[-Key, +Error, +Value] {

  /**
   * Returns a snapshot of the hit count, the miss count and the number of
   * entries of this cache.
   */
  def cacheStats: UIO[CacheStats]

  /**
   * Returns whether the cache has an entry for the specified key that has not
   * expired. A key whose resource is still being created counts as present,
   * even when that lookup is a refresh of a value that has expired.
   */
  def contains(key: Key): UIO[Boolean]

  /**
   * Returns statistics for the entry of the specified key, or `None` when there
   * is no entry or its lookup is still running, including a `get` that
   * recomputes an expired value. An expired entry that has not been removed yet
   * still reports its statistics.
   */
  def entryStats(key: Key): UIO[Option[EntryStats]]

  /**
   * Gets the resource for the specified key from the cache, or computes it with
   * the lookup function if there is none or it has expired. The resource is
   * acquired in the caller's `Scope`: closing that scope tells the cache that
   * the caller no longer uses it, and the resource's finalizers run once the
   * cache has also dropped the entry and no other scope holds it.
   */
  def get(key: Key): ZIO[Scope, Error, Value]

  /**
   * Computes a new resource for the specified key with the lookup function and
   * puts it in the cache. Once the new resource is ready, the old one is
   * released from the cache, and its finalizers run once all callers that
   * acquired it have closed their scopes. While the new resource is being
   * computed, `get` still returns the old one unless it has expired.
   *
   * If the lookup fails, the failure replaces the old resource, which is
   * released, and `get` returns that failure until its time to live passes.
   *
   * If a lookup for the key is already running, `refresh` waits for it instead
   * of starting another.
   */
  def refresh(key: Key): IO[Error, Unit]

  /**
   * Invalidates the resource associated with the specified key. Its finalizers
   * run once all callers that acquired it have closed their scopes.
   */
  def invalidate(key: Key): UIO[Unit]

  /**
   * Invalidates all values in the cache.
   */
  def invalidateAll: UIO[Unit]

  /**
   * Returns the approximate number of entries in the cache. Entries whose
   * lookup is still running, and expired entries that have not been removed
   * yet, by the background sweep or by a `get`, are counted too.
   */
  def size: UIO[Int]
}

/**
 * Constructors for [[ScopedCache]].
 */
object ScopedCache {

  /**
   * Constructs a new cache with the specified capacity, time to live, and
   * lookup function. Closing the `Scope` the cache is created in invalidates
   * every entry and stops the background sweep that releases expired entries.
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
   * returned by the lookup function. Closing the `Scope` the cache is created
   * in invalidates every entry and stops the background sweep that releases
   * expired entries.
   */
  def makeWith[Key, Environment, Error, Value](
    capacity: Int,
    scopedLookup: ScopedLookup[Key, Environment, Error, Value]
  )(timeToLive: Exit[Error, Value] => Duration): URIO[Environment with Scope, ScopedCache[Key, Error, Value]] =
    ZIO
      .acquireRelease(buildWith(capacity, scopedLookup)(timeToLive))(_.invalidateAll)
      .tap { scopedCache =>
        runFreeExpiredResourcesLoopInBackground(scopedCache)
      }

  private def runFreeExpiredResourcesLoopInBackground[Key, Environment, Error, Value](
    scopedCache: ScopedCacheImplementation[Key, Error, Value, Environment]
  ): URIO[Scope, Fiber.Runtime[Nothing, Long]] = {
    val cleaningInterval = 1.second
    (scopedCache.freeExpired *> ZIO.sleep(cleaningInterval)).forever.interruptible.forkScoped
  }

  private def buildWith[Key, Environment, Error, Value](
    capacity: Int,
    scopedLookup: ScopedLookup[Key, Environment, Error, Value]
  )(
    timeToLive: Exit[Error, Value] => Duration
  ): URIO[Environment, ScopedCacheImplementation[Key, Environment, Error, Value]] =
    ZIO.clockWith { clock =>
      ZIO.environmentWith[Environment] { environment =>
        new ScopedCacheImplementation(capacity, scopedLookup, timeToLive, clock, environment)
      }
    }

  /**
   * A `MapValue` represents a value in the cache. A value may either be
   * `Pending` with an effect that yields the resource once its lookup has run,
   * `Complete` with an `Exit` value that contains the result of computing the
   * lookup function, or `Refreshing` with the effect for the new resource while
   * the previous `Complete` value is still served.
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
    map: ConcurrentHashMap[Key, MapValue[Key, Error, Value]],
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
        new ConcurrentHashMap(),
        new KeySet,
        MutableConcurrentQueue.unbounded,
        new LongAdder,
        new LongAdder,
        new AtomicBoolean(false)
      )
  }

  /**
   * An action that closes the scope the lookup acquired a cached resource in,
   * running its finalizers with the `Exit` it is given.
   */
  type Finalizer = (=> Exit[Any, Any]) => UIO[Any]
}
