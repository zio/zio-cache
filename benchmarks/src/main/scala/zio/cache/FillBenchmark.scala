package zio.cache

import java.util.concurrent.TimeUnit

import scala.collection.immutable.Range

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import zio.{ BootstrapRuntime, ZIO }
import zio.internal.Platform
import scalacache._
import scalacache.caffeine._

/**
 * 8/26/2020:            FillBenchmark.zioCacheFill   10000  thrpt    3  65.278 ∩┐╜ 1.741  ops/s
 * (EntryStats):         FillBenchmark.zioCacheFill   10000  thrpt    3  58.841 ∩┐╜ 8.347  ops/s
 * (eviction):           FillBenchmark.zioCacheFill   10000  thrpt    3  40.000 ∩┐╜ 8.347  ops/s
 * (Promise.await):      FillBenchmark.zioCacheFill   10000  thrpt    3  82.770 ∩┐╜ 4.233  ops/s
 * (Pending | Complete): FillBenchmark.zioCacheFill   10000  thrpt    3  67.379 ∩┐╜ 1.381  ops/s
 *
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class FillBenchmark extends BootstrapRuntime {
  override val platform: Platform = Platform.benchmark

  @Param(Array("10000"))
  var size: Int = _

  var strings: Array[String] = _

  @Setup(Level.Trial)
  def initializeStrings() =
    strings = (0 until size).map(_.toString).toArray

  val identityLookup = Lookup[String, Any, Nothing, String](ZIO.succeed(_))

  @Benchmark
  def zioCacheFill() =
    unsafeRun(
      for {
        cache <- Cache.make(size, CachingPolicy.byLastAccess, Ttl.never, identityLookup)
        _     <- ZIO.foreach_(strings)(cache.get(_))
        size0 <- cache.size
        _     <- ZIO.effect(Predef.assert(size0 == size))
      } yield ()
    )

  // @Benchmark
  // def caffeineFill() = {
  //   import scala.concurrent.ExecutionContext.Implicits.global

  //   import scala.concurrent.Future
  //   import scalacache.modes.scalaFuture._

  //   implicit val cacheConfig = CacheConfig.defaultCacheConfig

  //   unsafeRun(
  //     for {
  //       cache <- ZIO.succeed(CaffeineCache[String])
  //       _     <- ZIO.foreach_(strings) { string =>
  //         ZIO.fromFuture { _ =>
  //           cache.doGet[Future](string).flatMap {
  //             case None => cache.doPut(string, string, None).flatMap(_ => Future(string))
  //             case Some(v) => Future(v)
  //           }
  //         }
  //       }
  //     } yield ()
  //   )
  // }
}
