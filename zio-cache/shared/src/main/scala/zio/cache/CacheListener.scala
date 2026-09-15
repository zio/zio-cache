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
import zio.{Exit, UIO}

import java.time.Duration
import java.time.temporal.ChronoUnit

/**
 * A `CacheListener` is notified of significant events in the lifecycle of a
 * cache, such as hits, misses, completed lookups, and evictions. It can be
 * used to export cache activity to a metrics backend or to any other
 * monitoring infrastructure.
 *
 * The effects returned by a listener are executed on the fiber interacting
 * with the cache, potentially on its hot path, so they should be fast and
 * non-blocking. A failure of a listener effect is logged and does not affect
 * the operation of the cache.
 */
trait CacheListener[-Key, -Error, -Value] {

  /**
   * Called when a value associated with the specified key is found in the
   * cache.
   */
  def onHit(key: Key): UIO[Unit]

  /**
   * Called when no value associated with the specified key is found in the
   * cache and the lookup function will be triggered.
   */
  def onMiss(key: Key): UIO[Unit]

  /**
   * Called when a lookup completes, whether triggered by `get` or by
   * `refresh`, with the `Exit` value produced by the lookup function and the
   * time the lookup took.
   */
  def onLoad(key: Key, exit: Exit[Error, Value], loadTime: Duration): UIO[Unit]

  /**
   * Called when an entry associated with the specified key is removed from
   * the cache.
   */
  def onEviction(key: Key, cause: CacheListener.EvictionCause): UIO[Unit]
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
    new CacheListener[Any, Any, Any] {
      def onHit(key: Any): UIO[Unit]                                            = Exit.unit
      def onMiss(key: Any): UIO[Unit]                                           = Exit.unit
      def onLoad(key: Any, exit: Exit[Any, Any], loadTime: Duration): UIO[Unit] = Exit.unit
      def onEviction(key: Any, cause: EvictionCause): UIO[Unit]                 = Exit.unit
    }

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

      def onHit(key: Any): UIO[Unit] =
        hits.update(1L)

      def onMiss(key: Any): UIO[Unit] =
        misses.update(1L)

      def onLoad(key: Any, exit: Exit[Any, Any], loadTime: Duration): UIO[Unit] =
        (if (exit.isSuccess) loadSuccesses.update(1L) else loadFailures.update(1L)) *>
          loadDuration.update(loadTime)

      def onEviction(key: Any, cause: EvictionCause): UIO[Unit] =
        cause match {
          case EvictionCause.Capacity    => capacityEvictions.update(1L)
          case EvictionCause.Expired     => expiredEvictions.update(1L)
          case EvictionCause.Invalidated => invalidatedEvictions.update(1L)
        }
    }
}
