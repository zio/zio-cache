---
id: entry-statistics
title: "Entry Statistics"
---

ZIO Cache also tracks statistics associated with each entry, such as when the entry was loaded into the cache. You can access the statistics for a specified entry using the `entryStats` operator on `Cache`. The operator takes the key of the entry and returns `None` when the cache holds no value for that key.

```scala mdoc
import zio._

import java.time.Instant

trait EntryStats {
  def loaded: Instant
}

trait Cache[-Key, +Error, +Value] {
  def entryStats(key: Key): UIO[Option[EntryStats]]
}
```

More entry statistics will be added over time.
