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

import zio.internal.MutableConcurrentQueue
import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.{Exit, IO, Promise, Trace, UIO, URIO, Unsafe, ZIO}

import java.time.{Duration, Instant}
import java.util.Map
import java.util.concurrent.atomic.{AtomicBoolean, LongAdder}

/**
 * A `Cache` is defined in terms of a lookup function that, given a key of
 * type `Key`, can either fail with an error of type `Error` or succeed with a
 * value of type `Value`. Getting a value from the cache will either return
 * the previous result of the lookup function if it is available or else
 * compute a new result with the lookup function, put it in the cache, and
 * return it.
 *
 * A cache also has a specified capacity and time to live. When the cache is
 * at capacity the least recently accessed values in the cache will be
 * removed to make room for new values. Getting a value with a life older than
 * the specified time to live will result in a new value being computed with
 * the lookup function and returned when available.
 *
 * The cache is safe for concurrent access. If multiple fibers attempt to get
 * the same key the lookup function will only be computed once and the result
 * will be returned to all fibers.
 */
abstract class Cache[-Key, +Error, +Value] {

  /**
   * Returns statistics for this cache.
   */
  def cacheStats(implicit trace: Trace): UIO[CacheStats]

  /**
   * Returns whether a value associated with the specified key exists in the
   * cache.
   */
  def contains(key: Key)(implicit trace: Trace): UIO[Boolean]

  /**
   * Returns statistics for the specified entry.
   */
  def entryStats(key: Key)(implicit trace: Trace): UIO[Option[EntryStats]]

  /**
   * Retrieves the value associated with the specified key if it exists.
   * Otherwise computes the value with the lookup function, puts it in the
   * cache, and returns it.
   */
  def get(key: Key)(implicit trace: Trace): IO[Error, Value]

  /**
   * Computes the value associated with the specified key, with the lookup
   * function, and puts it in the cache. The difference between this and
   * `get` method is that `refresh` triggers (re)computation of the value
   * without invalidating it in the cache, so any request to the associated
   * key can still be served while the value is being re-computed/retrieved
   * by the lookup function. Additionally, `refresh` always triggers the
   * lookup function, disregarding the last `Error`.
   */
  def refresh(key: Key): IO[Error, Unit]

  /**
   * Computes the value associated with the specified key, with the lookup
   * function, and puts it in the cache only if it is a value, not an error.
   */
  def refreshValue(key: Key): IO[Error, Unit]

  /**
   * Invalidates the value associated with the specified key.
   */
  def invalidate(key: Key)(implicit trace: Trace): UIO[Unit]

  /**
   * Invalidates all values in the cache.
   */
  def invalidateAll: UIO[Unit]

  /**
   * Returns the approximate number of values in the cache.
   */
  def size(implicit trace: Trace): UIO[Int]
}

object Cache {

  /**
   * Constructs a new cache with the specified capacity, time to live, and
   * lookup function.
   */
  def make[Key, Environment, Error, Value](
    capacity: Int,
    timeToLive: Duration,
    lookup: Lookup[Key, Environment, Error, Value]
  )(implicit trace: Trace): URIO[Environment, Cache[Key, Error, Value]] =
    makeWith(capacity, lookup)(_ => timeToLive)

  /**
   * Constructs a new cache with the specified capacity, time to live, and
   * lookup function, where the time to live can depend on the `Exit` value
   * returned by the lookup function.
   */
  def makeWith[Key, Environment, Error, Value](
    capacity: Int,
    lookup: Lookup[Key, Environment, Error, Value]
  )(
    timeToLive: Exit[Error, Value] => Duration
  )(implicit trace: Trace): URIO[Environment, Cache[Key, Error, Value]] =
    makeWithKey(capacity, lookup)(timeToLive, identity)

  /**
   * Constructs a new cache with the specified capacity, time to live, and
   * lookup function, where the time to live can depend on the `Exit` value
   * returned by the lookup function.
   *
   * This variant also allows specifying a custom keying function that will be
   * used to to convert the input of the lookup function into the key in the
   * underlying cache. This can be useful when the input to the lookup function
   * is large and you do not want to store it in the cache.
   */
  def makeWithKey[In, Key, Environment, Error, Value](
    capacity: Int,
    lookup: Lookup[In, Environment, Error, Value]
  )(
    timeToLive: Exit[Error, Value] => Duration,
    keyBy: In => Key
  )(implicit trace: Trace): URIO[Environment, Cache[In, Error, Value]] =
    ZIO.clock.flatMap { clock =>
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

          new Cache[In, Error, Value] {

            override def cacheStats(implicit trace: Trace): UIO[CacheStats] =
              ZIO.succeed(CacheStats(hits.longValue, misses.longValue, map.size))

            override def contains(in: In)(implicit trace: Trace): UIO[Boolean] =
              ZIO.succeed(map.containsKey(keyBy(in)))

            override def entryStats(in: In)(implicit trace: Trace): UIO[Option[EntryStats]] =
              ZIO.succeed {
                val value = map.get(keyBy(in))
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

            override def get(in: In)(implicit trace: Trace): IO[Error, Value] =
              ZIO.suspendSucceedUnsafe { implicit u =>
                val k                              = keyBy(in)
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
                  lookupValueOf(in, promise)
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
                        get(in)
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

            def refresh(in: In): IO[Error, Unit] =
              refresh(in, rollbackIfError = false)

            def refreshValue(in: In): IO[Error, Unit] =
              refresh(in, rollbackIfError = true)

            private def refresh(in: In, rollbackIfError: Boolean): IO[Error, Unit] =
              ZIO.suspendSucceedUnsafe { implicit u =>
                val k       = keyBy(in)
                val promise = newPromise()
                var value   = map.get(k)
                if (value eq null) {
                  value = map.putIfAbsent(k, MapValue.Pending(new MapKey(k), promise))
                }
                val result = if (value eq null) {
                  val rollbackResultIfError = if (rollbackIfError) Right(None) else Left(())
                  lookupValueOf(in, promise, rollbackResultIfError)
                } else {
                  value match {
                    case MapValue.Pending(_, promiseInProgress) =>
                      promiseInProgress.await
                    case completedResult @ MapValue.Complete(mapKey, _, _, ttl) =>
                      if (hasExpired(ttl)) {
                        map.remove(k, value)
                        get(in)
                      } else {
                        // Only trigger the lookup if we're still the current value, `completedResult`
                        val rollbackResultIfError = if (rollbackIfError) Right(Some(completedResult)) else Left(())
                        lookupValueOf(in, promise, rollbackResultIfError).when {
                          map.replace(k, completedResult, MapValue.Refreshing(promise, completedResult))
                        }
                      }
                    case MapValue.Refreshing(promiseInProgress, _) =>
                      promiseInProgress.await
                  }
                }
                result.unit
              }

            override def invalidate(in: In)(implicit trace: Trace): UIO[Unit] =
              ZIO.succeed {
                map.remove(keyBy(in))
                ()
              }

            override def invalidateAll: UIO[Unit] =
              ZIO.succeed {
                map.clear()
              }

            def size(implicit trace: Trace): UIO[Int] =
              ZIO.succeed(map.size)

            private def lookupValueOf(
              in: In,
              promise: Promise[Error, Value],
              /**
               * Left(()): Put the lookup result.
               * Right(None): Remove key if there is an error.
               * Right(Some(rollbackResult)): Rollback if there is an error.
               */
              rollbackResultIfError: Either[Unit, Option[MapValue.Complete[Key, Error, Value]]] = Left(())
            ): IO[Error, Value] =
              ZIO.suspendSucceed {
                val key = keyBy(in)
                lookup(in)
                  .provideEnvironment(environment)
                  .exit
                  .flatMap { exit =>
                    val now        = Unsafe.unsafe(implicit u => clock.unsafe.instant())
                    val entryStats = EntryStats(now)

                    if (exit.isSuccess)
                      map.put(key, MapValue.Complete(new MapKey(key), exit, entryStats, now.plus(timeToLive(exit))))
                    else
                      rollbackResultIfError match {
                        case Left(()) =>
                          map.put(key, MapValue.Complete(new MapKey(key), exit, entryStats, now.plus(timeToLive(exit))))
                        case Right(None) =>
                          map.remove(key)
                        case Right(Some(rollbackResult)) =>
                          map.put(key, rollbackResult)
                      }

                    promise.done(exit) *> ZIO.done(exit)
                  }
                  .onInterrupt(promise.interrupt *> ZIO.succeed(map.remove(key)))
              }

            private def newPromise()(implicit unsafe: Unsafe) =
              Promise.unsafe.make[Error, Value](fiberId)

            private def hasExpired(timeToLive: Instant)(implicit unsafe: Unsafe) =
              clock.unsafe.instant().isAfter(timeToLive)
          }
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
