package zio.cache

object Example {
  import CachingPolicy._

  val evict =
      Evict.greaterThan(100 * 1024 * 1024)   // smaller than 100 MB

  val policy =
    byLastAccess ++ bySize ++ fromEvict(evict)
}
