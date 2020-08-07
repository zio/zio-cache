package zio.cache

import java.time.Duration

object Example {
  import CachingPolicy._

  val evict =
    Evict.olderThan(Duration.ofHours(1L)) && // newer than 1 hour
      Evict.greaterThan(100 * 1024 * 1024)   // smaller than 100 MB

  val policy =
    byLastAccess ++ bySize ++ fromEvict(evict)
}
