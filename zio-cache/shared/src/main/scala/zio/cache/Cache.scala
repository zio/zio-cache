package zio.cache

import zio.internal.MutableConcurrentQueue
import zio.{Chunk, ChunkBuilder, Exit, IO, Promise, UIO, URIO, ZIO}

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
   * Retrieves the values associated with the specified keys if they exist.
   * Otherwise computes the values with the lookupfunction, puts them in the
   * cache, and returns them.
   */
  def getAll(keys: Iterable[Key]): IO[Error, Chunk[Value]]

  /**
   * Invalidates the value associated with the specified key.
   */
  def invalidate(key: Key): UIO[Unit]

  /**
   * Returns the approximate number of values in the cache.
   */
  def size: UIO[Int]

  /**
   * Retrieves the value associated with the specified key if it exists.
   * Otherwise computes the value with the lookup function, puts it in the
   * cache, and returns it.
   */
  final def get(key: Key): IO[Error, Value] =
    getAll(Chunk(key)).map(_.head)
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
  ): URIO[Environment, Cache[Key, Error, Value]] =
    ZIO.environment[Environment].flatMap { environment =>
      ZIO.fiberId.map { fiberId =>
        val cacheState = CacheState.initial[Key, Error, Value]()
        val map        = cacheState.map
        val keys       = cacheState.keys
        val accesses   = cacheState.accesses
        val hits       = cacheState.hits
        val misses     = cacheState.misses
        val updating   = cacheState.updating

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

          def cacheStats: UIO[CacheStats] =
            ZIO.succeed(CacheStats(hits.longValue, misses.longValue))

          def contains(k: Key): UIO[Boolean] =
            ZIO.succeed(map.containsKey(k))

          def entryStats(k: Key): UIO[Option[EntryStats]] =
            ZIO.succeed {
              val value = map.get(k)
              if (value eq null) None
              else {
                value match {
                  case MapValue.Pending(_, _)              => None
                  case MapValue.Complete(_, _, entryState) => Some(EntryStats(entryState.loaded))
                }
              }
            }

          def getAll(keys: Iterable[Key]): IO[Error, Chunk[Value]] =
            ZIO.effectSuspendTotal {
              val builder          = ChunkBuilder.make[MapValue.Pending[Key, Error, Value]]()
              val iterator         = keys.iterator
              val size             = keys.size
              val values           = Array.ofDim[MapValue[Key, Error, Value]](size)
              var i                = 0
              while (iterator.hasNext) {
                val key   = iterator.next()
                val value = map.get(key)
                if (value eq null) {
                  val promise = Promise.unsafeMake[Error, Value](fiberId)
                  val pending = MapValue.Pending(new MapKey(key), promise)
                  val value   = map.putIfAbsent(key, pending)
                  if (value eq null) {
                    trackMiss()
                    builder += pending
                    values(i) = pending
                  } else {
                    trackHit()
                    values(i) = value
                  }
                } else {
                  trackHit()
                  values(i) = value
                }
                i += 1
              }
              val pending          = builder.result()
              pending.foreach(pending => trackAccess(pending.key))
              val completePromises = lookup(pending.map(_.key.value))
                .provide(environment)
                .run
                .flatMap { exit =>
                  val now        = Instant.now()
                  val entryStats = EntryStats(now)

                  exit match {
                    case Exit.Success(values) =>
                      ZIO.foreach_(pending.zip(values)) { case (pending, value) =>
                        map.put(pending.key.value, MapValue.Complete(pending.key, Exit.succeed(value), entryStats))
                        pending.promise.succeed(value)
                      }

                    case Exit.Failure(cause) =>
                      ZIO.foreach_(pending) { pending =>
                        map.put(pending.key.value, MapValue.Complete(pending.key, Exit.halt(cause), entryStats))
                        pending.promise.halt(cause)
                      }
                  }
                }
                .onInterrupt(
                  ZIO.collectAll(pending.map(_.promise.interrupt)) *>
                    ZIO.succeed(pending.foreach(key => map.remove(key.key)))
                )
              val awaitResults     = IO.foreach(Chunk.fromArray(values)) { value =>
                value match {
                  case MapValue.Pending(key, promise)           =>
                    trackAccess(key)
                    promise.await
                  case MapValue.Complete(key, exit, entryState) =>
                    trackAccess(key)
                    val now  = Instant.now()
                    val life = Duration.between(entryState.loaded, now)
                    if (life.compareTo(timeToLive) >= 0) {
                      map.remove(key.value, value)
                      get(key.value)
                    } else {
                      ZIO.done(exit)
                    }
                }
              }

              completePromises *> awaitResults
            }

          def invalidate(k: Key): UIO[Unit] =
            ZIO.succeed {
              map.remove(k)
              ()
            }

          def size: UIO[Int] =
            ZIO.succeed(map.size)
        }
      }
    }

  /**
   * A `MapKey` represents a key in the cache. It contains mutable references
   * to the previous key and next key in the `KeySet` to support an efficient
   * implementation of a sorted set of keys.
   */
  private final class MapKey[Key](val value: Key, var previous: MapKey[Key] = null, var next: MapKey[Key] = null)

  /**
   * A `MapValue` represents a value in the cache. A value may either be
   * `Pending` with a `Promise` that will contain the result of computing the
   * lookup function, when it is available, or `Complete` with an `Exit` value
   * that contains the result of computing the lookup function.
   */
  private sealed trait MapValue[Key, Error, Value]

  private object MapValue {
    final case class Pending[Key, Error, Value](key: MapKey[Key], promise: Promise[Error, Value])
        extends MapValue[Key, Error, Value]
    final case class Complete[Key, Error, Value](key: MapKey[Key], exit: Exit[Error, Value], entryStats: EntryStats)
        extends MapValue[Key, Error, Value]
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
