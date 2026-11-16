package zio.cache

import zio._
import zio.test.Assertion._
import zio.test._

object CacheSpec extends ZIOSpecDefault {

  def hash(x: Int): Int => UIO[Int] =
    y => ZIO.succeed((x ^ y).hashCode)

  val identity: Int => UIO[Int] =
    ZIO.succeed(_)

  def spec: Spec[TestEnvironment with Scope, Any] = suite("CacheSpec")(
    test("cacheStats") {
      check(Gen.int) { salt =>
        for {
          cache      <- Cache.make(100, Duration.Infinity, Lookup(hash(salt)))
          _          <- ZIO.foreachParDiscard((1 to 100).map(_ / 2))(cache.get)
          cacheStats <- cache.cacheStats
          hits        = cacheStats.hits
          misses      = cacheStats.misses
        } yield assertTrue(hits == 49L) &&
          assertTrue(misses == 51L)
      }
    },
    test("invalidate") {
      check(Gen.int) { salt =>
        for {
          cache    <- Cache.make(100, Duration.Infinity, Lookup(hash(salt)))
          _        <- ZIO.foreachDiscard(1 to 100)(cache.get)
          _        <- cache.invalidate(42)
          contains <- cache.contains(42)
        } yield assert(contains)(isFalse)
      }
    },
    test("invalidateAll") {
      check(Gen.int) { salt =>
        for {
          cache <- Cache.make(100, Duration.Infinity, Lookup(hash(salt)))
          _     <- ZIO.foreachDiscard(1 to 100)(cache.get)
          _     <- cache.invalidateAll
          size  <- cache.size
        } yield assertTrue(size == 0)
      }
    },
    suite("lookup")(
      test("sequential") {
        check(Gen.int) { salt =>
          for {
            cache    <- Cache.make(100, Duration.Infinity, Lookup(hash(salt)))
            actual   <- ZIO.foreach(1 to 100)(cache.get)
            expected <- ZIO.foreach(1 to 100)(hash(salt))
          } yield assertTrue(actual == expected)
        }
      },
      test("concurrent") {
        check(Gen.int) { salt =>
          for {
            cache    <- Cache.make(100, Duration.Infinity, Lookup(hash(salt)))
            actual   <- ZIO.foreachPar(1 to 100)(cache.get)
            expected <- ZIO.foreachPar(1 to 100)(hash(salt))
          } yield assertTrue(actual == expected)
        }
      },
      test("capacity") {
        check(Gen.int) { salt =>
          for {
            cache    <- Cache.make(10, Duration.Infinity, Lookup(hash(salt)))
            actual   <- ZIO.foreachPar(1 to 100)(cache.get)
            expected <- ZIO.foreachPar(1 to 100)(hash(salt))
          } yield assertTrue(actual == expected)
        }
      }
    ),
    suite("`refresh` method")(
      test("should update the cache with a new value") {
        def inc(n: Int) = n * 10
        def retrieve(multiplier: Ref[Int])(key: Int) =
          multiplier
            .updateAndGet(inc)
            .map(key * _)

        val seed = 1
        val key  = 123
        for {
          ref   <- Ref.make(seed)
          cache <- Cache.make(1, Duration.Infinity, Lookup(retrieve(ref)))
          val1  <- cache.get(key)
          _     <- cache.refresh(key)
          _     <- cache.get(key)
          val2  <- cache.get(key)
        } yield assertTrue(val1 == inc(key)) && assertTrue(val2 == inc(val1))
      },
      test("should update the cache with a new value even if the last `get` or `refresh` failed") {

        val error = new RuntimeException("Must be a multiple of 3")

        def inc(n: Int) = n + 1
        def retrieve(number: Ref[Int])(key: Int) =
          number
            .updateAndGet(inc)
            .flatMap {
              case n if n % 3 == 0 =>
                ZIO.fail(error)
              case n =>
                ZIO.succeed(key * n)
            }

        val seed = 2
        val key  = 1
        for {
          ref      <- Ref.make(seed)
          cache    <- Cache.make(1, Duration.Infinity, Lookup(retrieve(ref)))
          failure1 <- cache.get(key).either
          _        <- cache.refresh(key)
          val1     <- cache.get(key).either
          _        <- cache.refresh(key)
          failure2 <- cache.refresh(key).either
          _        <- cache.refresh(key)
          val2     <- cache.get(key).either
        } yield assert(failure1)(isLeft(equalTo(error))) &&
          assert(failure2)(isLeft(equalTo(error))) &&
          assert(val1)(isRight(equalTo(4))) &&
          assert(val2)(isRight(equalTo(7)))
      },
      test("should get the value if the key doesn't exist in the cache") {
        check(Gen.int) { salt =>
          val cap = 100
          for {
            cache  <- Cache.make(cap, Duration.Infinity, Lookup(hash(salt)))
            count0 <- cache.size
            _      <- ZIO.foreachDiscard(1 to cap)(cache.refresh)
            count1 <- cache.size
          } yield assertTrue(count0 == 0) && assertTrue(count1 == cap)
        }
      },
      test("stores a value read after refresh is called when a lookup is in progress") {
        for {
          store   <- Ref.make("old")
          calls   <- Ref.make(0)
          started <- Promise.make[Nothing, Unit]
          gate    <- Promise.make[Nothing, Unit]
          cache <- Cache.make(
                     100,
                     Duration.Infinity,
                     Lookup((_: Int) =>
                       calls.updateAndGet(_ + 1).flatMap { n =>
                         store.get.flatMap(v => if (n == 1) started.succeed(()) *> gate.await.as(v) else ZIO.succeed(v))
                       }
                     )
                   )
          getter    <- cache.get(1).fork
          _         <- started.await
          _         <- store.set("new")
          refresher <- cache.refresh(1).fork
          _ <- refresher.status
                 .repeatUntil(s => s.isInstanceOf[Fiber.Status.Suspended] || s == Fiber.Status.Done)
                 .timeout(5.seconds)
                 .withClock(Clock.ClockLive)
          _     <- gate.succeed(())
          _     <- getter.join *> refresher.join
          value <- cache.get(1)
        } yield assertTrue(value == "new")
      },
      test("can be interrupted while it waits for a lookup in progress") {
        for {
          calls   <- Ref.make(0)
          started <- Promise.make[Nothing, Unit]
          cache <- Cache.make(
                     100,
                     Duration.Infinity,
                     Lookup((_: Int) => calls.updateAndGet(_ + 1) *> started.succeed(()) *> ZIO.never)
                   )
          _         <- cache.get(1).fork
          _         <- started.await
          refresher <- cache.refresh(1).fork
          _ <- refresher.status
                 .repeatUntil(_.isInstanceOf[Fiber.Status.Suspended])
                 .timeout(5.seconds)
                 .withClock(Clock.ClockLive)
          exit     <- refresher.interrupt.timeout(5.seconds).withClock(Clock.ClockLive)
          contains <- cache.contains(1)
          n        <- calls.get
        } yield assertTrue(exit.exists(_.isInterrupted), contains, n == 1)
      },
      test("waits for the value of a refresh that starts its lookup first") {
        for {
          runtime <- ZIO.runtime[Any]
          calls   <- Ref.make(0)
          started <- Promise.make[Nothing, Unit]
          gate    <- Promise.make[Nothing, Unit]
          cache <- Cache.make(
                     100,
                     Duration.Infinity,
                     Lookup((_: HookKey) =>
                       calls
                         .updateAndGet(_ + 1)
                         .flatMap(n => if (n == 2) started.succeed(()) *> gate.await.as(n) else ZIO.succeed(n))
                     )
                   )
          one = new HookKey(1)
          _  <- cache.get(one)
          // `refresh` hashes `one` to read its entry, then again to replace it: another refresh starts its lookup in
          // between
          _ <- ZIO.succeed(one.onHash(2) {
                 Unsafe.unsafe { implicit unsafe =>
                   runtime.unsafe.run(cache.refresh(one).forkDaemon *> started.await).getOrThrowFiberFailure()
                 }
               })
          refresher <- cache.refresh(one).fork
          _ <- refresher.status
                 .repeatUntil(s => s.isInstanceOf[Fiber.Status.Suspended] || s == Fiber.Status.Done)
                 .timeout(5.seconds)
                 .withClock(Clock.ClockLive)
          done  <- refresher.poll.map(_.isDefined)
          _     <- gate.succeed(())
          _     <- refresher.join
          value <- cache.get(one)
          n     <- calls.get
        } yield assertTrue(!done, value == 2, n == 2)
        // The hook waits for the other refresh to start its lookup, which JS cannot do
      } @@ TestAspect.exceptJS,
      test("runs its own lookup when the lookup of the refresh it waits for is interrupted") {
        for {
          runtime <- ZIO.runtime[Any]
          calls   <- Ref.make(0)
          started <- Promise.make[Nothing, Unit]
          other   <- Promise.make[Nothing, Fiber.Runtime[Nothing, Unit]]
          cache <-
            Cache.make(
              100,
              Duration.Infinity,
              Lookup((_: HookKey) =>
                calls.updateAndGet(_ + 1).flatMap(n => if (n == 2) started.succeed(()) *> ZIO.never else ZIO.succeed(n))
              )
            )
          one = new HookKey(1)
          _  <- cache.get(one)
          // `refresh` hashes `one` to read its entry, then again to replace it: another refresh starts its lookup in
          // between
          _ <- ZIO.succeed(one.onHash(2) {
                 Unsafe.unsafe { implicit unsafe =>
                   runtime.unsafe
                     .run(cache.refresh(one).forkDaemon.flatMap(other.succeed) *> started.await)
                     .getOrThrowFiberFailure()
                 }
               })
          refresher <- cache.refresh(one).fork
          _ <- refresher.status
                 .repeatUntil(s => s.isInstanceOf[Fiber.Status.Suspended] || s == Fiber.Status.Done)
                 .timeout(5.seconds)
                 .withClock(Clock.ClockLive)
          _     <- other.await.flatMap(_.interrupt)
          exit  <- refresher.join.exit
          value <- cache.get(one)
        } yield assertTrue(exit == Exit.unit, value == 3)
        // The hook waits for the other refresh to start its lookup, which JS cannot do
      } @@ TestAspect.exceptJS,
      test("runs its own lookup when the refresh it waits for is interrupted") {
        for {
          calls   <- Ref.make(0)
          started <- Promise.make[Nothing, Unit]
          cache <-
            Cache.make(
              100,
              Duration.Infinity,
              Lookup((_: Int) =>
                calls.updateAndGet(_ + 1).flatMap(n => if (n == 2) started.succeed(()) *> ZIO.never else ZIO.succeed(n))
              )
            )
          _      <- cache.get(1)
          first  <- cache.refresh(1).fork
          _      <- started.await
          second <- cache.refresh(1).fork
          _ <- second.status
                 .repeatUntil(_.isInstanceOf[Fiber.Status.Suspended])
                 .timeout(5.seconds)
                 .withClock(Clock.ClockLive)
          _     <- first.interrupt
          _     <- second.join
          value <- cache.get(1)
        } yield assertTrue(value == 3)
      },
      test("does not count a hit or miss when it refreshes again after waiting for a lookup") {
        for {
          calls   <- Ref.make(0)
          started <- Promise.make[Nothing, Unit]
          gate    <- Promise.make[Nothing, Unit]
          lookup = Lookup((_: Int) =>
                     calls.updateAndGet(_ + 1).flatMap { n =>
                       if (n == 1) started.succeed(()) *> gate.await *> ZIO.fail("failure") else ZIO.succeed(n)
                     }
                   )
          // A failure expires at once, so the refresh finds it expired when it refreshes again
          cache <- Cache.makeWith(100, lookup) {
                     case Exit.Success(_) => Duration.Infinity
                     case Exit.Failure(_) => Duration.fromNanos(-1)
                   }
          getter    <- cache.get(1).fork
          _         <- started.await
          refresher <- cache.refresh(1).fork
          _ <- refresher.status
                 .repeatUntil(_.isInstanceOf[Fiber.Status.Suspended])
                 .timeout(5.seconds)
                 .withClock(Clock.ClockLive)
          _     <- gate.succeed(())
          _     <- getter.join.exit *> refresher.join
          stats <- cache.cacheStats
          n     <- calls.get
        } yield assertTrue(stats.hits == 0L, stats.misses == 1L, n == 2)
      }
    ),
    test("size") {
      check(Gen.int) { salt =>
        for {
          cache <- Cache.make(10, Duration.Infinity, Lookup(hash(salt)))
          _     <- ZIO.foreachDiscard(1 to 100)(cache.get)
          size  <- cache.size
        } yield assertTrue(size == 10)
      }
    },
    test("ensure get is not hanging when we interrupt right after a get in another fiber") {
      val n = 100000
      for {
        cache <- Cache.make(10, Duration.Infinity, Lookup((_: Unit) => ZIO.unit))
        task1  = (cache.invalidate(()) *> cache.get(())).fork.flatMap(_.interrupt)
        _     <- ZIO.collectAll(List.fill(n)(task1))
        _     <- (cache.get(()).exit.timeoutFail("hanging")(3.seconds)).replicateZIODiscard(n)
      } yield assertCompletes
    } @@ TestAspect.withLiveClock @@ TestAspect.timeout(20.seconds),
    test("cache hits should not be incremented for expired entries") {
      for {
        cache      <- Cache.make(100, 1.second, Lookup(identity))
        _          <- cache.get(42)
        _          <- TestClock.adjust(2.seconds)
        _          <- cache.get(42)
        cacheStats <- cache.cacheStats
        hits        = cacheStats.hits
        misses      = cacheStats.misses
      } yield assertTrue(hits == 0L) &&
        assertTrue(misses == 2L)
    },
    suite("`contains` method")(
      test("should return false for expired entries") {
        for {
          cache     <- Cache.make(100, 1.second, Lookup(identity))
          _         <- cache.get(42)
          contains1 <- cache.contains(42)
          _         <- TestClock.adjust(2.seconds)
          contains2 <- cache.contains(42)
        } yield assertTrue(contains1) && assertTrue(!contains2)
      },
      test("should return true during ongoing lookup") {
        for {
          started  <- Promise.make[Nothing, Unit]
          promise  <- Promise.make[Nothing, Int]
          cache    <- Cache.make(100, Duration.Infinity, Lookup((_: Int) => started.succeed(()) *> promise.await))
          _        <- cache.get(42).fork
          _        <- started.await
          contains <- cache.contains(42)
          _        <- promise.succeed(42)
        } yield assertTrue(contains)
      }
    ),
    suite("key tracking")(
      test("does not grow when an entry expires and is reloaded") {
        for {
          cache <- Cache.make(10, 1.second, Lookup(identity))
          _     <- (cache.get(42) *> TestClock.adjust(2.seconds)).repeatN(99)
        } yield assertTrue(keyCount(cache) <= 2)
      },
      test("does not grow when an entry is invalidated and reloaded") {
        for {
          cache <- Cache.make(10, Duration.Infinity, Lookup(identity))
          _     <- (cache.get(42) *> cache.invalidate(42)).repeatN(99)
        } yield assertTrue(keyCount(cache) <= 2)
      },
      test("does not grow when an entry is refreshed") {
        for {
          cache <- Cache.make(10, Duration.Infinity, Lookup(identity))
          _     <- (cache.refresh(42) *> cache.get(42)).repeatN(99)
        } yield assertTrue(keyCount(cache) == 1)
      },
      test("evicts the least recently used entry") {
        for {
          cache <- Cache.make(2, Duration.Infinity, Lookup(identity))
          _     <- cache.get(1) *> cache.get(2) *> cache.get(1) *> cache.get(3)
          one   <- cache.contains(1)
          two   <- cache.contains(2)
          three <- cache.contains(3)
        } yield assertTrue(one, !two, three)
      },
      test("evicts an entry added by refresh") {
        for {
          cache <- Cache.make(1, Duration.Infinity, Lookup(identity))
          _     <- cache.refresh(1) *> cache.get(2)
          one   <- cache.contains(1)
          two   <- cache.contains(2)
        } yield assertTrue(!one, two)
      },
      test("evicts an entry that changes state while it is being evicted") {
        for {
          runtime <- ZIO.runtime[Any]
          calls   <- Ref.make(0)
          // A new value on every call, so the refreshed entry is not equal to the one being evicted
          cache <- Cache.make(1, Duration.Infinity, Lookup((_: HookKey) => calls.updateAndGet(_ + 1)))
          one    = new HookKey(1)
          _     <- cache.get(one)
          // Eviction hashes `one` to read its entry, then again to remove it: refresh the entry in between
          // (refreshing a stored entry does not track an access, so it does not re-enter the eviction)
          _ <- ZIO.succeed(one.onHash(2) {
                 Unsafe.unsafe(implicit unsafe => runtime.unsafe.run(cache.refresh(one)).getOrThrowFiberFailure())
               })
          _      <- cache.get(new HookKey(2))
          size   <- cache.size
          hasOne <- cache.contains(one)
        } yield assertTrue(size == 1, !hasOne, keyCount(cache) == 1)
      },
      test("get completes when it is called during an eviction on the same thread") {
        for {
          runtime <- ZIO.runtime[Any]
          cache   <- Cache.make(2, Duration.Infinity, Lookup((key: HookKey) => ZIO.succeed(key.id)))
          one      = new HookKey(1)
          _       <- cache.get(one) *> cache.get(new HookKey(2))
          // Eviction hashes `one` to read its entry: call `get` from there, while the eviction holds the cache
          _ <- ZIO.succeed(one.onHash(1) {
                 Unsafe.unsafe(implicit unsafe =>
                   runtime.unsafe.run(cache.get(new HookKey(4))).getOrThrowFiberFailure()
                 )
               })
          // Disconnected so that the timeout fires even if the nested `get` spins without yielding
          completed <- cache.get(new HookKey(3)).disconnect.timeout(10.seconds).map(_.isDefined)
          size      <- cache.size
        } yield assertTrue(completed, size == 2)
      } @@ TestAspect.withLiveClock
    ),
    suite("lookup in progress")(
      test("does not store its result if the entry is invalidated") {
        for {
          store   <- Ref.make("stale")
          started <- Promise.make[Nothing, Unit]
          gate    <- Promise.make[Nothing, Unit]
          cache <- Cache.make(
                     100,
                     Duration.Infinity,
                     Lookup((_: Int) => store.get.flatMap(v => started.succeed(()) *> gate.await.as(v)))
                   )
          fiber <- cache.get(1).fork
          _     <- started.await
          _     <- store.set("fresh")
          _     <- cache.invalidate(1)
          _     <- gate.succeed(())
          first <- fiber.join
          out   <- cache.get(1)
        } yield assertTrue(first == "stale", out == "fresh")
      },
      test("does not store its result if the entry is evicted") {
        for {
          started <- Promise.make[Nothing, Unit]
          gate    <- Promise.make[Nothing, Unit]
          cache <- Cache.make(
                     1,
                     Duration.Infinity,
                     Lookup((key: Int) => if (key == 1) started.succeed(()) *> gate.await.as(key) else ZIO.succeed(key))
                   )
          fiber <- cache.get(1).fork
          _     <- started.await
          _     <- cache.get(2)
          _     <- gate.succeed(())
          _     <- fiber.join
          size  <- cache.size
          one   <- cache.contains(1)
        } yield assertTrue(size == 1, !one)
      },
      test("runs the lookup again after it is interrupted") {
        for {
          calls   <- Ref.make(0)
          started <- Promise.make[Nothing, Unit]
          cache <- Cache.make(
                     100,
                     Duration.Infinity,
                     Lookup((key: Int) =>
                       calls
                         .getAndUpdate(_ + 1)
                         .flatMap(n => if (n == 0) started.succeed(()) *> ZIO.never else ZIO.succeed(key))
                     )
                   )
          fiber <- cache.get(1).fork
          _     <- started.await
          _     <- fiber.interrupt
          value <- cache.get(1)
          n     <- calls.get
        } yield assertTrue(value == 1, n == 2)
      },
      test("does not remove a newer entry when interrupted") {
        for {
          calls   <- Ref.make(0)
          started <- Promise.make[Nothing, Unit]
          cache <- Cache.make(
                     100,
                     Duration.Infinity,
                     Lookup((key: Int) =>
                       calls
                         .getAndUpdate(_ + 1)
                         .flatMap(n => if (n == 0) started.succeed(()) *> ZIO.never else ZIO.succeed(key))
                     )
                   )
          fiber <- cache.get(1).fork
          _     <- started.await
          _     <- cache.invalidate(1)
          _     <- cache.get(1)
          _     <- fiber.interrupt
          _     <- cache.get(1)
          n     <- calls.get
        } yield assertTrue(n == 2)
      },
      test("keeps the previous value when a refresh is interrupted") {
        for {
          calls   <- Ref.make(0)
          started <- Promise.make[Nothing, Unit]
          cache <- Cache.make(
                     100,
                     Duration.Infinity,
                     Lookup((key: Int) =>
                       calls
                         .getAndUpdate(_ + 1)
                         .flatMap(n => if (n == 1) started.succeed(()) *> ZIO.never else ZIO.succeed(key))
                     )
                   )
          _        <- cache.get(1)
          fiber    <- cache.refresh(1).fork
          _        <- started.await
          _        <- fiber.interrupt
          contains <- cache.contains(1)
          _        <- cache.get(1)
          n        <- calls.get
        } yield assertTrue(contains, n == 2)
      },
      test("a get waiting on a lookup is not interrupted when the fiber running the lookup is") {
        for {
          calls   <- Ref.make(0)
          started <- Promise.make[Nothing, Unit]
          cache <- Cache.make(
                     100,
                     Duration.Infinity,
                     Lookup((key: Int) =>
                       calls
                         .getAndUpdate(_ + 1)
                         .flatMap(n => if (n == 0) started.succeed(()) *> ZIO.never else ZIO.succeed(key))
                     )
                   )
          owner  <- cache.get(1).fork
          _      <- started.await
          waiter <- cache.get(1).fork
          // The waiter counts a hit once it has read the pending entry
          _     <- cache.cacheStats.repeatUntil(_.hits == 1L).timeout(5.seconds).withClock(Clock.ClockLive)
          _     <- owner.interrupt
          exit  <- waiter.await.timeout(5.seconds).withClock(Clock.ClockLive)
          stats <- cache.cacheStats
        } yield assertTrue(exit.contains(Exit.succeed(1)), stats.hits == 1L, stats.misses == 1L)
      },
      test("a get waiting for the refresh of an expired value gets a value when the refresh is interrupted") {
        for {
          calls   <- Ref.make(0)
          started <- Promise.make[Nothing, Unit]
          cache <- Cache.make(
                     100,
                     10.seconds,
                     Lookup((key: Int) =>
                       calls
                         .getAndUpdate(_ + 1)
                         .flatMap(n => if (n == 1) started.succeed(()) *> ZIO.never else ZIO.succeed(key))
                     )
                   )
          _       <- cache.get(1)
          refresh <- cache.refresh(1).fork
          _       <- started.await
          _       <- TestClock.adjust(11.seconds)
          waiter  <- cache.get(1).fork
          // The waiter counts a hit once it has read the refreshing entry
          _    <- cache.cacheStats.repeatUntil(_.hits == 1L).timeout(5.seconds).withClock(Clock.ClockLive)
          _    <- refresh.interrupt
          exit <- waiter.await.timeout(5.seconds).withClock(Clock.ClockLive)
          n    <- calls.get
        } yield assertTrue(exit.contains(Exit.succeed(1)), n == 3)
      },
      test("a refresh waiting on a lookup whose fiber is interrupted loads the value") {
        for {
          calls   <- Ref.make(0)
          started <- Promise.make[Nothing, Unit]
          cache <- Cache.make(
                     100,
                     Duration.Infinity,
                     Lookup((key: Int) =>
                       calls
                         .getAndUpdate(_ + 1)
                         .flatMap(n => if (n == 0) started.succeed(()) *> ZIO.never else ZIO.succeed(key))
                     )
                   )
          owner   <- cache.get(1).fork
          _       <- started.await
          refresh <- cache.refresh(1).fork
          _ <- refresh.status
                 .repeatUntil(_.isInstanceOf[Fiber.Status.Suspended])
                 .timeout(5.seconds)
                 .withClock(Clock.ClockLive)
          _     <- owner.interrupt
          exit  <- refresh.await.timeout(5.seconds).withClock(Clock.ClockLive)
          value <- cache.get(1)
        } yield assertTrue(exit.contains(Exit.unit), value == 1)
      },
      test("the fibers waiting for a lookup that interrupts itself get its interruption, which is not cached") {
        for {
          calls <- Ref.make(0)
          gate  <- Promise.make[Nothing, Unit]
          cache <-
            Cache.make(100, Duration.Infinity, Lookup((_: Int) => calls.update(_ + 1) *> gate.await *> ZIO.interrupt))
          owner   <- cache.get(1).fork
          _       <- calls.get.repeatUntil(_ == 1).timeout(5.seconds).withClock(Clock.ClockLive)
          waiters <- ZIO.foreach((1 to 4).toList)(_ => cache.get(1).fork)
          // Each waiter counts a hit once it has read the pending entry
          _        <- cache.cacheStats.repeatUntil(_.hits == 4L).timeout(5.seconds).withClock(Clock.ClockLive)
          _        <- gate.succeed(())
          exits    <- ZIO.foreach(owner :: waiters)(_.await).timeout(5.seconds).withClock(Clock.ClockLive)
          lookups  <- calls.get
          contains <- cache.contains(1)
          next     <- cache.get(1).exit
          after    <- calls.get
        } yield assertTrue(
          exits.exists(_.forall(_.isInterrupted)),
          lookups == 1,
          !contains,
          next.isInterrupted,
          after == 2
        )
      },
      test("a later get does not see the interruption of a lookup whose finalizer fails") {
        for {
          calls   <- Ref.make(0)
          started <- Promise.make[Nothing, Unit]
          cache <- Cache.make(
                     100,
                     Duration.Infinity,
                     Lookup((key: Int) =>
                       calls.getAndUpdate(_ + 1).flatMap { n =>
                         if (n == 0)
                           (started.succeed(()) *> ZIO.never).ensuring(ZIO.die(new RuntimeException("finalizer")))
                         else ZIO.succeed(key)
                       }
                     )
                   )
          owner  <- cache.get(1).fork
          _      <- started.await
          waiter <- cache.get(1).fork
          // The waiter counts a hit once it has read the pending entry
          _      <- cache.cacheStats.repeatUntil(_.hits == 1L).timeout(5.seconds).withClock(Clock.ClockLive)
          _      <- owner.interrupt
          waited <- waiter.await.timeout(5.seconds).withClock(Clock.ClockLive)
          second <- cache.get(1).exit
        } yield assertTrue(waited.contains(Exit.succeed(1)), second == Exit.succeed(1))
      },
      test("a lookup that fails in parallel with an interruption caches its failure") {
        for {
          calls <- Ref.make(0)
          cache <- Cache.make(
                     100,
                     Duration.Infinity,
                     Lookup((key: Int) => calls.update(_ + 1) *> (ZIO.fail("boom") <&> ZIO.never).as(key))
                   )
          first  <- cache.get(1).exit
          second <- cache.get(1).exit
          n      <- calls.get
        } yield assertTrue(
          // The other side of the lookup is interrupted by the fiber running the lookup, which is not interrupted
          first.causeOption.exists(cause => cause.isInterrupted && cause.failureOption.contains("boom")),
          second.causeOption.flatMap(_.failureOption).contains("boom"),
          n == 1
        )
      }
    )
  )

  private def keyCount(cache: Cache[?, ?, ?]): Int =
    cache.asInstanceOf[Cache.CacheImplementation[?, ?, ?, ?, ?]].keyCount
}
