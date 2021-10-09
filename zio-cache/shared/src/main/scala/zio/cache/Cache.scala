package zio.cache

import zio.internal.MutableConcurrentQueue
import zio.stream.{UStream, ZStream}
import zio.{Chunk, Exit, Hub, IO, Promise, UIO, URIO, ZIO}

import java.time.{Duration, Instant}
import java.util.Map
import java.util.concurrent.atomic.{AtomicBoolean, LongAdder}
import scala.collection.mutable.ArrayBuffer

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
sealed abstract class Cache[-Key, +Error, +Value] {

  /**
   * Returns statistics for this cache.
   */
  def cacheStats: UIO[CacheStats]

  /**
   * Returns whether a value associated with the specified key exists in the
   * cache.
   */
  def contains(key: Key): UIO[Boolean]

  /**
   * Returns statistics for the specified entry.
   */
  def entryStats(key: Key): UIO[Option[EntryStats]]

  /**
   * Returns a stream of cache invalidation events.
   */
  def invalidationEvents: UStream[InvalidationEvent]

  /**
   * Retrieves the value associated with the specified key if it exists.
   * Otherwise computes the value with the lookup function, puts it in the
   * cache, and returns it.
   */
  def get(key: Key): IO[Error, Value]

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
   * Invalidates the value associated with the specified key.
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

object Cache {

  private val invalidationEventHubCapacity = 128

  /**
   * Constructs a new cache with the specified capacity, time to live, and
   * lookup function.
   */
  def make[Key, Environment, Error, Value](
    capacity: Int,
    timeToLive: Duration,
    lookup: Lookup[Key, Environment, Error, Value]
  ): URIO[Environment, Cache[Key, Error, Value]] =
    makeWith(capacity, lookup)(_ => timeToLive)

  /**
   * Constructs a new cache with the specified capacity, time to live, and
   * lookup function, where the time to live can depend on the `Exit` value
   * returned by the lookup function.
   */
  def makeWith[Key, Environment, Error, Value](
    capacity: Int,
    lookup: Lookup[Key, Environment, Error, Value]
  )(timeToLive: Exit[Error, Value] => Duration): URIO[Environment, Cache[Key, Error, Value]] =
    for {
      environment          <- ZIO.environment[Environment]
      fiberId              <- ZIO.fiberId
      invalidationEventHub <- Hub.bounded[InvalidationEvent](invalidationEventHubCapacity)
    } yield {
      val cacheState = CacheState.initial[Key, Error, Value]()
      import cacheState._

      def trackAccess(key: MapKey[Key]): Chunk[Key] = {
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
          var size   = map.size
          val buffer = new ArrayBuffer[Key](size - capacity)
          loop = size > capacity
          while (loop) {
            val key = keys.remove()
            if (key ne null) {
              val k = key.value
              if (map.remove(k) ne null) {
                size -= 1
                loop = size > capacity
                buffer += k
              }
            } else {
              loop = false
            }
          }

          val invalidated = Chunk.fromIterable(buffer)
          updating.set(false)
          invalidated
        } else {
          Chunk.empty
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

        override def invalidationEvents: UStream[InvalidationEvent] =
          ZStream.fromHub(invalidationEventHub)

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
              val evicted = trackAccess(key)
              trackMiss()
              publishOverCapInvalidation(evicted) *>
                lookupValueOf(k, promise)
            } else {
              value match {
                case MapValue.Pending(key, promise) =>
                  val evicted = trackAccess(key)
                  trackHit()
                  publishOverCapInvalidation(evicted) *>
                    promise.await
                case MapValue.Complete(key, exit, _, timeToLive) =>
                  val evicted = trackAccess(key)
                  trackHit()
                  if (hasExpired(timeToLive))
                    publishOverCapInvalidation(evicted) *>
                      evictIfEqual(k, value) *>
                      get(k)
                  else
                    publishOverCapInvalidation(evicted) *>
                      ZIO.done(exit)
                case MapValue.Refreshing(
                      promiseInProgress,
                      MapValue.Complete(mapKey, currentResult, _, ttl)
                    ) =>
                  val evicted = trackAccess(mapKey)
                  trackHit()
                  publishOverCapInvalidation(evicted) *> (
                    if (hasExpired(ttl))
                      promiseInProgress.await
                    else
                      ZIO.done(currentResult)
                  )
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
                    evictIfEqual(k, value) *> get(k)
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
          publishInvalidation(k).when(
            map.remove(k) ne null
          )

        override def invalidateAll: UIO[Unit] =
          invalidationEventHub
            .publish(
              InvalidationEvent.InvalidationAll(map.size)
            )
            .as(map.clear())

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
              promise.interrupt *> invalidate(key)
            )

        private def newPromise() =
          Promise.unsafeMake[Error, Value](fiberId)

        private def hasExpired(timeToLive: Instant) =
          Instant.now().isAfter(timeToLive)

        private def publishInvalidation(key: Key) =
          invalidationEventHub.publish(
            InvalidationEvent.SingleInvalidation(key)
          )

        private def evictIfEqual(k: Key, value: MapValue[Key, Error, Value]) =
          publishInvalidation(k).when(
            map.remove(k, value)
          )

        private def publishOverCapInvalidation(keys: Chunk[Key]): UIO[Unit] =
          if (keys.nonEmpty)
            invalidationEventHub
              .publish(
                InvalidationEvent.OverCapacityInvalidation(keys)
              )
              .unit
          else
            ZIO.unit
      }
    }

  /**
   * A `MapKey` represents a key in the cache. It contains mutable references
   * to the previous key and next key in the `KeySet` to support an efficient
   * implementation of a sorted set of keys.
   */
  private final class MapKey[Key](
    val value: Key,
    var previous: MapKey[Key] = null,
    var next: MapKey[Key] = null
  )

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

  /**
   * A `KeySet` is a sorted set of keys in the cache ordered by last access.
   * For efficiency, the set is implemented in terms of a doubly linked list
   * and is not safe for concurrent access.
   */
  private final class KeySet[Key] {
    private[this] var head: MapKey[Key] = null
    private[this] var tail: MapKey[Key] = null

    /**
     * Adds the specified key to the set.
     */
    def add(key: MapKey[Key]): Unit =
      if (key ne tail) {
        if (tail ne null) {
          val previous = key.previous
          val next     = key.next
          if (next ne null) {
            key.next = null
            if (previous ne null) {
              previous.next = next
              next.previous = previous
            } else {
              head = next
              head.previous = null
            }
          }
          tail.next = key
          key.previous = tail
          tail = key
        } else {
          head = key
          tail = key
        }
      }

    /**
     * Removes the lowest priority key from the set.
     */
    def remove(): MapKey[Key] = {
      val key = head
      if (key ne null) {
        val next = key.next
        if (next ne null) {
          key.next = null
          head = next
          head.previous = null
        } else {
          head = null
          tail = null
        }
      }
      key
    }
  }
}
