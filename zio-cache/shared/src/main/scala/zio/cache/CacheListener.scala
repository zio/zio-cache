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

import zio.metrics.Metric
import zio.{Exit, Unsafe}

import java.time.Duration
import java.time.temporal.ChronoUnit

/**
 * A `CacheListener` is notified of significant events in the lifecycle of a
 * cache, such as hits, misses, completed lookups, and evictions. It can be
 * used to export cache activity to a metrics backend or to any other
 * monitoring infrastructure.
 *
 * Listener methods are invoked synchronously on the fiber interacting with
 * the cache, potentially on its hot path, so implementations must be fast,
 * non-blocking, and must not throw exceptions. Any exceptions thrown by a
 * listener will be ignored. All methods have no-op default implementations
 * so implementations only need to override the events they are interested
 * in.
 */
trait CacheListener[-Key, -Error, -Value] {

  /**
   * Called when a value associated with the specified key is found in the
   * cache.
   */
  def onHit(key: Key)(implicit unsafe: Unsafe): Unit =
    ()

  /**
   * Called when no value associated with the specified key is found in the
   * cache and the lookup function will be triggered.
   */
  def onMiss(key: Key)(implicit unsafe: Unsafe): Unit =
    ()

  /**
   * Called when a lookup completes, whether triggered by `get` or by
   * `refresh`, with the `Exit` value produced by the lookup function and the
   * time the lookup took.
   */
  def onLoad(key: Key, exit: Exit[Error, Value], loadTime: Duration)(implicit unsafe: Unsafe): Unit =
    ()

  /**
   * Called when an entry associated with the specified key is removed from
   * the cache. Note that no events are emitted by `invalidateAll`.
   */
  def onEviction(key: Key, cause: CacheListener.EvictionCause)(implicit unsafe: Unsafe): Unit =
    ()
}

object CacheListener {

  /**
   * The cause of the removal of an entry from the cache.
   */
  sealed trait EvictionCause extends Product with Serializable

  object EvictionCause {

    /**
     * The entry was removed because the cache was at capacity and the entry
     * was one of the least recently accessed.
     */
    case object Capacity extends EvictionCause

    /**
     * The entry was removed because it was older than its time to live.
     */
    case object Expired extends EvictionCause

    /**
     * The entry was removed because it was explicitly invalidated.
     */
    case object Invalidated extends EvictionCause
  }

  /**
   * A listener that ignores all events.
   */
  val noop: CacheListener[Any, Any, Any] =
    new CacheListener[Any, Any, Any] {}

  /**
   * A listener that reports cache events with ZIO metrics, tagging each
   * metric with the specified cache name. The following metrics are
   * reported:
   *
   *   - `<prefix>hits` — counter of cache hits
   *   - `<prefix>misses` — counter of cache misses
   *   - `<prefix>load_successes` — counter of successfully completed lookups
   *   - `<prefix>load_failures` — counter of failed lookups
   *   - `<prefix>load_duration` — histogram of lookup durations
   *   - `<prefix>evictions` — counter of evictions, tagged with the eviction
   *     cause
   */
  def metrics(cacheName: String, prefix: String = "zio_cache_"): CacheListener[Any, Any, Any] =
    new CacheListener[Any, Any, Any] {
      private val hits          = Metric.counter(prefix + "hits").tagged("cache_name", cacheName)
      private val misses        = Metric.counter(prefix + "misses").tagged("cache_name", cacheName)
      private val loadSuccesses = Metric.counter(prefix + "load_successes").tagged("cache_name", cacheName)
      private val loadFailures  = Metric.counter(prefix + "load_failures").tagged("cache_name", cacheName)
      private val loadDuration =
        Metric.timer(prefix + "load_duration", ChronoUnit.MILLIS).tagged("cache_name", cacheName)
      private val evictions = Metric.counter(prefix + "evictions").tagged("cache_name", cacheName)

      private val capacityEvictions    = evictions.tagged("cause", "capacity")
      private val expiredEvictions     = evictions.tagged("cause", "expired")
      private val invalidatedEvictions = evictions.tagged("cause", "invalidated")

      override def onHit(key: Any)(implicit unsafe: Unsafe): Unit =
        hits.unsafe.update(1L)

      override def onMiss(key: Any)(implicit unsafe: Unsafe): Unit =
        misses.unsafe.update(1L)

      override def onLoad(key: Any, exit: Exit[Any, Any], loadTime: Duration)(implicit unsafe: Unsafe): Unit = {
        if (exit.isSuccess) loadSuccesses.unsafe.update(1L)
        else loadFailures.unsafe.update(1L)
        loadDuration.unsafe.update(loadTime)
      }

      override def onEviction(key: Any, cause: EvictionCause)(implicit unsafe: Unsafe): Unit =
        cause match {
          case EvictionCause.Capacity    => capacityEvictions.unsafe.update(1L)
          case EvictionCause.Expired     => expiredEvictions.unsafe.update(1L)
          case EvictionCause.Invalidated => invalidatedEvictions.unsafe.update(1L)
        }
    }
}
