package zio.cache

import java.time.{ Duration, Instant }

/**
 * Maximum size of cache (e.g. 10,000 elements)
 * Eviction around various criteria
   - Fixed date expiration
   - Time since creation?
   - Time since last access? (how often is it used?)
   - Time since refresh? (how old is it?)
   - Entry-level statistics? (Hits/misses)
   - References to the entry? (any references left?)
   - Size of entry? (how much memory does it consume?)
   - Cache-wide statistics?
   - Value-based criteria?
 * Notification of eviction
 * Ability to "write" loaded values, e.g. store on disk after retrieving remote values
 * LRU / Adapative / etc.
 */
final case class EntryStats(
  added: Instant,
  accessed: Instant,
  loaded: Instant,
  hits: Long,
  misses: Long,
  loads: Long,
  curSize: Long,
  accSize: Long,
  accLoading: Duration
) {
  def total: Long = hits + misses

  def size: Long = accSize / loads
}
object EntryStats {
  def make(now: Instant): EntryStats = 
    EntryStats(now, now, now, 1L, 0L, 1L, 0L, 0L, Duration.ZERO)
}