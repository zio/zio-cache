package zio.cache

import zio._
import zio.cache.CacheListener.EvictionCause
import zio.metrics.Metric
import zio.test._

import java.time.{Duration => JDuration}
import java.util.concurrent.atomic.AtomicInteger

object CacheListenerSpec extends ZIOSpecDefault {

  final class TestListener extends CacheListener[Any, Any, Any] {
    val hits          = new AtomicInteger(0)
    val misses        = new AtomicInteger(0)
    val loadSuccesses = new AtomicInteger(0)
    val loadFailures  = new AtomicInteger(0)
    val capacity      = new AtomicInteger(0)
    val expired       = new AtomicInteger(0)
    val invalidated   = new AtomicInteger(0)

    override def onHit(key: Any)(implicit unsafe: Unsafe): Unit = {
      hits.incrementAndGet()
      ()
    }

    override def onMiss(key: Any)(implicit unsafe: Unsafe): Unit = {
      misses.incrementAndGet()
      ()
    }

    override def onLoad(key: Any, exit: Exit[Any, Any], loadTime: JDuration)(implicit unsafe: Unsafe): Unit = {
      if (exit.isSuccess) loadSuccesses.incrementAndGet() else loadFailures.incrementAndGet()
      ()
    }

    override def onEviction(key: Any, cause: EvictionCause)(implicit unsafe: Unsafe): Unit = {
      cause match {
        case EvictionCause.Capacity    => capacity.incrementAndGet()
        case EvictionCause.Expired     => expired.incrementAndGet()
        case EvictionCause.Invalidated => invalidated.incrementAndGet()
      }
      ()
    }
  }

  def hash(x: Int): Int => UIO[Int] =
    y => ZIO.succeed((x ^ y).hashCode)

  val identity: Int => UIO[Int] =
    ZIO.succeed(_)

  def spec: Spec[TestEnvironment with Scope, Any] = suite("CacheListenerSpec")(
    test("hit, miss, and load events") {
      check(Gen.int) { salt =>
        val listener = new TestListener
        for {
          cache <- Cache.make(100, Duration.Infinity, Lookup(hash(salt)), listener)
          _     <- ZIO.foreachParDiscard((1 to 100).map(_ / 2))(cache.get)
        } yield assertTrue(
          listener.hits.get == 49,
          listener.misses.get == 51,
          listener.loadSuccesses.get == 51,
          listener.loadFailures.get == 0
        )
      }
    },
    test("failed loads are cached and reported once") {
      val error    = new RuntimeException("boom")
      val listener = new TestListener
      for {
        cache    <- Cache.make(100, Duration.Infinity, Lookup((_: Int) => ZIO.fail(error)), listener)
        failure1 <- cache.get(42).either
        failure2 <- cache.get(42).either
      } yield assertTrue(
        failure1 == Left(error),
        failure2 == Left(error),
        listener.misses.get == 1,
        listener.hits.get == 1,
        listener.loadFailures.get == 1,
        listener.loadSuccesses.get == 0
      )
    },
    test("load events are emitted for refreshes") {
      val listener = new TestListener
      for {
        cache <- Cache.make(100, Duration.Infinity, Lookup(identity), listener)
        _     <- cache.get(42)
        _     <- cache.refresh(42)
      } yield assertTrue(
        listener.loadSuccesses.get == 2,
        listener.misses.get == 1
      )
    },
    test("eviction events when the cache is at capacity") {
      check(Gen.int) { salt =>
        val listener = new TestListener
        for {
          cache <- Cache.make(10, Duration.Infinity, Lookup(hash(salt)), listener)
          _     <- ZIO.foreachDiscard(1 to 100)(cache.get)
          size  <- cache.size
        } yield assertTrue(
          size == 10,
          listener.capacity.get == 90,
          listener.expired.get == 0,
          listener.invalidated.get == 0
        )
      }
    },
    test("eviction events for expired entries") {
      val listener = new TestListener
      for {
        cache <- Cache.make(100, 1.second, Lookup(identity), listener)
        _     <- cache.get(42)
        _     <- TestClock.adjust(2.seconds)
        _     <- cache.get(42)
      } yield assertTrue(
        listener.expired.get == 1,
        listener.misses.get == 2,
        listener.hits.get == 0
      )
    },
    test("eviction events for invalidated entries") {
      val listener = new TestListener
      for {
        cache <- Cache.make(100, Duration.Infinity, Lookup(identity), listener)
        _     <- cache.get(42)
        _     <- cache.invalidate(42)
        _     <- cache.invalidate(43)
      } yield assertTrue(listener.invalidated.get == 1)
    },
    test("listener is notified with the keys produced by the keying function") {
      val listener = new TestListener
      for {
        cache <- Cache.makeWithKey(100, Lookup((in: (Int, String)) => ZIO.succeed(in._2)), listener)(
                   _ => Duration.Infinity,
                   keyBy = _._1
                 )
        _ <- cache.get((42, "a"))
        _ <- cache.get((42, "b"))
        a <- cache.get((42, "c"))
      } yield assertTrue(
        a == "a",
        listener.misses.get == 1,
        listener.hits.get == 2
      )
    },
    test("a listener that throws does not affect the cache") {
      val listener = new CacheListener[Any, Any, Any] {
        override def onHit(key: Any)(implicit unsafe: Unsafe): Unit  = throw new RuntimeException("onHit")
        override def onMiss(key: Any)(implicit unsafe: Unsafe): Unit = throw new RuntimeException("onMiss")
        override def onLoad(key: Any, exit: Exit[Any, Any], loadTime: JDuration)(implicit u: Unsafe): Unit =
          throw new RuntimeException("onLoad")
        override def onEviction(key: Any, cause: EvictionCause)(implicit unsafe: Unsafe): Unit =
          throw new RuntimeException("onEviction")
      }
      for {
        cache <- Cache.make(100, Duration.Infinity, Lookup(identity), listener)
        a     <- cache.get(42)
        b     <- cache.get(42)
        _     <- cache.invalidate(42)
        stats <- cache.cacheStats
      } yield assertTrue(a == 42, b == 42, stats.hits == 1L, stats.misses == 1L)
    },
    test("metrics listener reports cache events with ZIO metrics") {
      val name = "metrics-listener-spec"
      for {
        cache  <- Cache.make(100, Duration.Infinity, Lookup(identity), CacheListener.metrics(name))
        _      <- ZIO.foreachDiscard(List(1, 1, 2))(cache.get)
        _      <- cache.invalidate(1)
        hits   <- Metric.counter("zio_cache_hits").tagged("cache_name", name).value
        misses <- Metric.counter("zio_cache_misses").tagged("cache_name", name).value
        loads  <- Metric.counter("zio_cache_load_successes").tagged("cache_name", name).value
        evictions <- Metric
                       .counter("zio_cache_evictions")
                       .tagged("cache_name", name)
                       .tagged("cause", "invalidated")
                       .value
      } yield assertTrue(
        hits.count == 1d,
        misses.count == 2d,
        loads.count == 2d,
        evictions.count == 1d
      )
    }
  )
}
