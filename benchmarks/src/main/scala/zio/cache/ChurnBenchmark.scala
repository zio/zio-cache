package zio.cache

import java.util.concurrent.TimeUnit

import scala.collection.immutable.Range

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import zio.{ BootstrapRuntime, ZIO }
import zio.internal.Platform
import scalacache.{ Cache => SCache, _ }
import scalacache.caffeine._

/**
 * (Baseline)            ChurnBenchmark.zioCacheChurn   10000  thrpt    3  113.703 ∩┐╜ 2.392  ops/s
 * (Pending | Complete): ChurnBenchmark.zioCacheChurn   10000  thrpt    3  105.944 ∩┐╜ 5.873  ops/s
 *
 * [info] ChurnBenchmark.zioCacheChurn   10000  thrpt    3  0.018 ∩┐╜ 0.001  ops/s
 * [info] ChurnBenchmark.zioCacheChurn   10000  thrpt    3  0.059 ∩┐╜ 0.015  ops/s
 *
 * [info] ChurnBenchmark.zioCacheChurn   10000  thrpt    3  49.796 ∩┐╜ 7.342  ops/s
 *
 * [info] ChurnBenchmark.zioCacheChurn   10000  thrpt    3  51.576 ∩┐╜ 25.941  ops/s
 *
 * [info] ChurnBenchmark.zioCacheChurn   10000  thrpt   25  117.802 ± 3.116  ops/s
 * [info] FillBenchmark.zioCacheFill     10000  thrpt   25   56.941 ± 1.149  ops/s
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
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
        cache <- Cache.make(size, CachingPolicy.byLastAccess.flip, Ttl.never, identityLookup)
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
