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
}
