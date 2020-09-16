package zio.cache

final case class Entry[+Value](entryStats: EntryStats, value: Value)
