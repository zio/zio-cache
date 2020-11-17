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
)
object CacheStats {
  val initial = CacheStats(0, 0L, 0L, 0L, 0L, 0L, Duration.ZERO)

  val addHit: CacheStats => CacheStats = v => v.copy(hits = v.hits + 1L)

  val addMiss: CacheStats => CacheStats = v => v.copy(misses = v.misses + 1L)

  def addLoadTime(time: Long): CacheStats => CacheStats = v => v.copy(loads = v.loads + time)

  def addEviction: CacheStats => CacheStats = v => v.copy(evictions = v.evictions + 1L)

  def addTotalLoadTime(time: Duration): CacheStats => CacheStats =
    v => v.copy(totalLoadTime = v.totalLoadTime.plus(time))
}
