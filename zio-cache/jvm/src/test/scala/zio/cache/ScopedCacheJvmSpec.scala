package zio.cache

import zio.test._
import zio.{Clock, Duration, Executor, Fiber, Promise, Ref, Scope, ZIO, durationInt}

import java.util.concurrent.Executors

/**
 * ScopedCache tests that need a single-threaded executor, which only the JVM
 * has.
 */
object ScopedCacheJvmSpec extends ZIOSpecDefault {

  def spec: Spec[TestEnvironment with Scope, Any] = suite("ScopedCacheJvmSpec")(
    test("a fiber waiting uninterruptibly for a value released before it acquires it looks it up uninterruptibly") {
      for {
        calls       <- Ref.make(0)
        started     <- Promise.make[Nothing, Unit]
        gate        <- Promise.make[Nothing, Unit]
        started2    <- Promise.make[Nothing, Unit]
        gate2       <- Promise.make[Nothing, Unit]
        interrupted <- Ref.make(false)
        lookup = ScopedLookup((key: Int) =>
                   calls.getAndUpdate(_ + 1).flatMap { n =>
                     if (n == 0)
                       started.succeed(()) *> gate.await *> ZIO.acquireRelease(ZIO.succeed(key))(_ => ZIO.unit)
                     else (started2.succeed(()) *> gate2.await).onInterrupt(interrupted.set(true)).as(key)
                   }
                 )
        // On one thread, the owner acquires and releases the value before the waiter runs again
        executor   <- ZIO.acquireRelease(ZIO.succeed(Executors.newSingleThreadExecutor()))(e => ZIO.succeed(e.shutdown()))
        onOneThread = Executor.fromJavaExecutor(executor)
        result <- ZIO.scoped {
                    ScopedCache.make(100, Duration.Infinity, lookup).flatMap { cache =>
                      for {
                        owner  <- ZIO.scoped(cache.get(1)).onExecutor(onOneThread).fork
                        _      <- started.await
                        waiter <- ZIO.uninterruptible(ZIO.scoped(cache.get(1))).onExecutor(onOneThread).fork
                        _ <- waiter.status
                               .repeatUntil(_.isInstanceOf[Fiber.Status.Suspended])
                               .timeout(5.seconds)
                               .withClock(Clock.ClockLive)
                        // The value is not stored, so the cache releases its share once the owner has acquired it
                        _       <- cache.invalidate(1)
                        _       <- gate.succeed(())
                        _       <- owner.join
                        retried <- started2.await.timeout(5.seconds).withClock(Clock.ClockLive)
                        stopped <- waiter.interrupt.timeout(1.second).withClock(Clock.ClockLive)
                        _       <- gate2.succeed(())
                        _       <- waiter.await
                        flag    <- interrupted.get
                      } yield assertTrue(retried.isDefined, stopped.isEmpty, !flag)
                    }
                  }
      } yield result
    }
  )
}
