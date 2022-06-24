package zio.cache

import org.openjdk.jmh.annotations.{Scope, _}
import zio._

import java.util.concurrent.TimeUnit

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Measurement(iterations = 5, timeUnit = TimeUnit.SECONDS, time = 3)
@Warmup(iterations = 5, timeUnit = TimeUnit.SECONDS, time = 3)
@Fork(1)
class ChurnBenchmark extends zio.Runtime[Any] {
  override val environment: ZEnvironment[Any] = ZEnvironment.empty
  override val fiberRefs: FiberRefs           = FiberRefs.empty
  override val runtimeFlags: RuntimeFlags     = RuntimeFlags.default

  @Param(Array("10000"))
  var size: Int = _

  var newEntries: Array[String]             = _
  var cache: Cache[String, Nothing, String] = _

  val identityLookup: Lookup[String, Any, Nothing, String] = Lookup[String, Any, Nothing, String](ZIO.succeed(_))

  @Setup(Level.Trial)
  def initialize(): Unit = {
    newEntries = (size until (2 * size)).map(_.toString).toArray
    val strings = (0 until size).map(_.toString).toArray

    Unsafe.unsafe { implicit u =>
      cache = unsafe
        .run(
          for {
            cache <- Cache.make(size, Duration.Infinity, identityLookup)
            _     <- ZIO.foreachDiscard(strings)(cache.get(_))
          } yield cache
        )
        .getOrThrowFiberFailure()
    }
  }

  @Benchmark
  def zioCacheChurn(): Unit =
    Unsafe.unsafe { implicit u =>
      unsafe
        .run(
          for {
            _ <- ZIO.foreachDiscard(newEntries)(cache.get(_))
          } yield ()
        )
        .getOrThrowFiberFailure()
    }

}
