package zio.cache

import java.time.Duration

final case class CacheStats(
  entryCount: Int,
  memorySize: Long,
  hits: Long,
  misses: Long,
  loads: Long,
  evictions: Long,
  totalLoadTime: Duration
) { self =>

  def addEviction: CacheStats =
    self.copy(evictions = self.evictions + 1L)

  def addLoad: CacheStats =
    self.copy(loads = loads + 1L)

  def addLoadTime(loadTime: Duration): CacheStats =
    self.copy(totalLoadTime = totalLoadTime.plus(loadTime))

  def addMiss: CacheStats =
    self.copy(misses = misses + 1L)

  def addHit: CacheStats =
    self.copy(hits = hits + 1L)
}

object CacheStats {

  val initial: CacheStats =
    CacheStats(0, 0L, 0L, 0L, 0L, 0L, Duration.ZERO)

  val addHit: CacheStats => CacheStats =
    _.addHit

  val addMiss: CacheStats => CacheStats =
    _.addMiss

  val addLoad: CacheStats => CacheStats =
    _.addLoad

  val addEviction: CacheStats => CacheStats =
    _.addEviction

  def addTotalLoadTime(time: Duration): CacheStats => CacheStats =
    _.addLoadTime(time)
}
