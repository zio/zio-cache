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
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, LongAdder}
import scala.jdk.CollectionConverters._

abstract class ScopedCache[-Key, +Error, +Value] {

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
    ZIO
      .environment[Environment]
      .map { environment =>
        new ScopedCacheImplementation(capacity, scopedLookup, timeToLive, environment)
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
