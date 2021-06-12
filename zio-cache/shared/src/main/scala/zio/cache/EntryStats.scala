package zio.cache

import java.time.Instant

/**
 * `EntryStats` represents a snapshot of statistics for an entry in the cache.
 */
final case class EntryStats(loaded: Instant)
