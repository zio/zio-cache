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
class FillBenchmark extends zio.Runtime[ZEnv] {
  override def environment: ZEnvironment[ZEnv] = ZEnv.Services.live
  override def runtimeConfig: RuntimeConfig    = RuntimeConfig.benchmark

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
        _     <- ZIO.foreachDiscard(strings)(cache.get(_))
      } yield ()
    )
}
