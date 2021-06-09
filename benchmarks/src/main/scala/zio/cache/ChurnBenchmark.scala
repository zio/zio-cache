package zio.cache

import java.util.concurrent.TimeUnit
import zio.duration._

import scala.collection.immutable.Range

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import zio.{ BootstrapRuntime, ZIO }
import zio.internal.Platform
import scalacache.{ Cache => SCache, _ }
import scalacache.caffeine._

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Measurement(iterations = 5, timeUnit = TimeUnit.SECONDS, time = 3)
@Warmup(iterations = 5, timeUnit = TimeUnit.SECONDS, time = 3)
@Fork(1)
class ChurnBenchmark extends BootstrapRuntime {
  override val platform: Platform = Platform.benchmark

  @Param(Array("10000"))
  var size: Int = _

  var newEntries: Array[String]             = _
  var cache: Cache[String, Nothing, String] = _

  val identityLookup = Lookup[String, Any, Nothing, String](ZIO.succeed(_))

  @Setup(Level.Trial)
  def initialize() = {
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
  def zioCacheChurn() =
    unsafeRun(
      for {
        _ <- ZIO.foreach_(newEntries)(cache.get(_))
        // evicted <- cache.contains("0")
        // _       <- ZIO.effect(Predef.assert(!evicted))
      } yield ()
    )
}
