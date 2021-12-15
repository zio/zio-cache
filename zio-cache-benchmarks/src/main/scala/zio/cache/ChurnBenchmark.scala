package zio.cache

import org.openjdk.jmh.annotations._
import zio._

import java.util.concurrent.TimeUnit

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Measurement(iterations = 5, timeUnit = TimeUnit.SECONDS, time = 3)
@Warmup(iterations = 5, timeUnit = TimeUnit.SECONDS, time = 3)
@Fork(1)
class ChurnBenchmark extends zio.Runtime[ZEnv] {
  override def environment: ZEnvironment[ZEnv] = ZEnv.Services.live
  override def runtimeConfig: RuntimeConfig    = RuntimeConfig.benchmark

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
        _     <- ZIO.foreachDiscard(strings)(cache.get(_))
      } yield cache
    )
  }

  @Benchmark
  def zioCacheChurn(): Unit =
    unsafeRun(
      for {
        _ <- ZIO.foreachDiscard(newEntries)(cache.get(_))
      } yield ()
    )

}
