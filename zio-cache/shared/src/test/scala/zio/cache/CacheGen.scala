package zio.cache

import java.time._
import java.time.temporal.ChronoUnit

import zio.random._
import zio.test._

object CacheGen {

  lazy val genBoolean: Gen[Random, Boolean] =
    Gen.boolean

  lazy val genCacheStats: Gen[Random, CacheStats] =
    for {
      entryCount    <- genPositiveInt
      memorySize    <- genPositiveLong
      hits          <- genPositiveLong
      misses        <- genPositiveLong
      loads         <- genPositiveLong
      evictions     <- genPositiveLong
      totalLoadTime <- genDuration
    } yield CacheStats(
      entryCount,
      memorySize,
      hits,
      misses,
      loads,
      evictions,
      totalLoadTime
    )

  lazy val genCachingPolicy: Gen[Random, CachingPolicy[Any]] =
    for {
      priority <- genPriority
      evict    <- genEvict
    } yield CachingPolicy(priority, evict)

  lazy val genDuration: Gen[Random, Duration] =
    genPositiveLong.map(Duration.of(_, ChronoUnit.NANOS))

  lazy val genEntry: Gen[Random, Entry[Any]] =
    for {
      entryStats <- genEntryStats
      value      <- Gen.anyInt
    } yield Entry(entryStats, value)

  lazy val genEntryStats: Gen[Random, EntryStats] =
    for {
      added      <- genInstant
      accessed   <- genInstant
      loaded     <- genInstant
      hits       <- genPositiveLong
      loads      <- genPositiveLong
      curSize    <- genPositiveLong
      accSize    <- genPositiveLong
      accLoading <- genDuration
    } yield EntryStats(
      added,
      accessed,
      loaded,
      hits,
      loads,
      curSize,
      accSize,
      accLoading
    )

  lazy val genEvict: Gen[Random, Evict[Any]] =
    Gen.function2[Random, Instant, Entry[Any], Boolean](genBoolean).map(Evict(_))

  lazy val genInstant: Gen[Random, Instant] =
    Gen.anyInstant

  lazy val genPositiveInt: Gen[Random, Int] =
    Gen.int(0, Int.MaxValue)

  lazy val genPositiveLong: Gen[Random, Long] =
    Gen.long(0, Long.MaxValue)

  lazy val genPriority: Gen[Random, Priority[Any]] =
    Gen.function2[Random, Entry[Any], Entry[Any], Int](Gen.elements(-1, 0, 1)).map(Priority(_))
}
