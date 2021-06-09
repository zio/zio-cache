package zio.cache

import java.util.{ HashMap, Map }

private object Platform {

  def newConcurrentMap[K, V]: Map[K, V] =
    new HashMap[K, V]()
}
