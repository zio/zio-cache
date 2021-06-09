package zio.cache

import zio.{ Exit, IO, Promise, UIO, URIO, ZIO }
import zio.internal.MutableConcurrentQueue

import java.time.{ Duration, Instant }
import java.util.Map
import java.util.concurrent.atomic.{ AtomicBoolean, LongAdder }

trait Cache[-Key, +Error, +Value] {

  def get(key: Key): IO[Error, Value]

  def size: UIO[Int]

  def contains(key: Key): UIO[Boolean]

  def cacheStats: UIO[CacheStats]

  def entryStats(key: Key): UIO[Option[EntryStats]]
}

object Cache {

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

          def get(k: Key): IO[Error, Value] =
            ZIO.effectSuspendTotal {
              var key: MapKey[Key]               = null
              var promise: Promise[Error, Value] = null
              var value                          = map.get(k)
              if (value eq null) {
                promise = Promise.unsafeMake[Error, Value](fiberId)
                key = new MapKey(k)
                value = map.putIfAbsent(k, MapValue.Pending(key, promise))
              }
              if (value eq null) {
                trackAccess(key)
                trackMiss()
                lookup(k)
                  .provide(environment)
                  .run
                  .flatMap { exit =>
                    val now        = Instant.now()
                    val entryStats = EntryStats(now)

                    map.put(k, MapValue.Complete(key, exit, entryStats))
                    promise.done(exit) *> ZIO.done(exit)
                  }
                  .onInterrupt(promise.interrupt *> ZIO.succeed(map.remove(k)))
              } else {
                value match {
                  case MapValue.Pending(key, promise) =>
                    trackAccess(key)
                    trackHit()
                    promise.await
                  case MapValue.Complete(key, exit, entryState) =>
                    trackAccess(key)
                    trackHit()
                    val now  = Instant.now()
                    val life = Duration.between(entryState.loaded, now)
                    if (life.compareTo(timeToLive) >= 0) {
                      map.remove(k, value)
                      get(k)
                    } else {
                      ZIO.done(exit)
                    }
                }
              }

            }

          def contains(k: Key): UIO[Boolean] =
            ZIO.succeed(map.containsKey(k))

          def size: UIO[Int] =
            ZIO.succeed(map.size)

          def cacheStats: UIO[CacheStats] =
            ZIO.succeed(CacheStats(hits.longValue, misses.longValue))

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
        }
      }
    }

  private final class MapKey[Key](val value: Key, var previous: MapKey[Key] = null, var next: MapKey[Key] = null)

  private sealed trait MapValue[Key, Error, Value]

  private object MapValue {
    final case class Pending[Key, Error, Value](key: MapKey[Key], promise: Promise[Error, Value])
        extends MapValue[Key, Error, Value]
    final case class Complete[Key, Error, Value](key: MapKey[Key], exit: Exit[Error, Value], entryStats: EntryStats)
        extends MapValue[Key, Error, Value]
  }

  private final case class CacheState[Key, Error, Value](
    map: Map[Key, MapValue[Key, Error, Value]],
    keys: KeySet[Key],
    accesses: MutableConcurrentQueue[MapKey[Key]],
    hits: LongAdder,
    misses: LongAdder,
    updating: AtomicBoolean
  )

  private object CacheState {
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

  private final case class EntryState(@volatile var loaded: Instant)

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
