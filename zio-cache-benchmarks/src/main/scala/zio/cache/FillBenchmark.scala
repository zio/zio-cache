package zio.cache

import org.openjdk.jmh.annotations._
import zio.duration._
import zio.{BootstrapRuntime, ZIO}

import java.util.concurrent.TimeUnit

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Measurement(iterations = 5, timeUnit = TimeUnit.SECONDS, time = 3)
@Warmup(iterations = 5, timeUnit = TimeUnit.SECONDS, time = 3)
@Fork(1)
class FillBenchmark extends BootstrapRuntime {
  override val platform: zio.internal.Platform = zio.internal.Platform.benchmark

  @Param(Array("10000"))
  var size: Int = _

  var strings: Array[String] = _

  @Setup(Level.Trial)
  def initializeStrings(): Unit =
    strings = (0 until size).map(_.toString).toArray

  val identityLookup: Lookup[String, Any, Nothing, String] = Lookup[String, Any, Nothing, String](ZIO.succeed(_))

  @Benchmark
  def zioCacheFill(): Unit =
    unsafeRun(
      for {
        cache <- Cache.make(size, Duration.Infinity, identityLookup)
        _     <- ZIO.foreach_(strings)(cache.get(_))
      } yield ()
    )
}
