package zio.cache

import java.util.concurrent.TimeUnit

import scala.collection.immutable.Range

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import zio.{ BootstrapRuntime, ZIO }
import zio.duration._
import zio.internal.Platform
import scalacache._
import scalacache.caffeine._

@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Measurement(iterations = 5, timeUnit = TimeUnit.SECONDS, time = 3)
@Warmup(iterations = 5, timeUnit = TimeUnit.SECONDS, time = 3)
@Fork(1)
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
        cache <- Cache.make(size, Duration.Infinity, identityLookup)
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
