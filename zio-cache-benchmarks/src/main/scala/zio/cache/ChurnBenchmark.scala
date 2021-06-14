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
class ChurnBenchmark extends BootstrapRuntime {
  override val platform: zio.internal.Platform = zio.internal.Platform.benchmark

  @Param(Array("10000"))
  var size: Int = _

  var newEntries: Array[String]             = _
  var cache: Cache[String, Nothing, String] = _

  val identityLookup: Lookup[String, Any, Nothing, String] = Lookup[String, Any, Nothing, String](ZIO.succeed(_))

  @Setup(Level.Trial)
  def initialize(): Unit = {
    newEntries = (size until (2 * size)).map(_.toString).toArray
    val strings = (0 until size).map(_.toString).toArray

    cache = unsafeRun(
      for {
        cache <- Cache.make(size, Duration.Infinity, identityLookup)
        _     <- ZIO.foreach_(strings)(cache.get(_))
      } yield cache
    )
  }

  @Benchmark
  def zioCacheChurn(): Unit =
    unsafeRun(
      for {
        _ <- ZIO.foreach_(newEntries)(cache.get(_))
      } yield ()
    )
}
