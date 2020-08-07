package zio.cache

final case class Entry[+Value](cacheStats: CacheStats, entryStats: EntryStats, value: Value)
