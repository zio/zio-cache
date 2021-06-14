package zio.cache

import java.util.Map
import java.util.concurrent.ConcurrentHashMap

private object Platform {

  /**
   * Constructs a new concurrent map.=
   */
  def newConcurrentMap[K, V]: Map[K, V] =
    new ConcurrentHashMap[K, V]()
}
