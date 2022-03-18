package zio.cache

/**
 * `CacheStats` represents a snapshot of statistics for the cache as of a
 * point in time.
 */
final case class CacheStats(hits: Long, misses: Long, size: Int)
