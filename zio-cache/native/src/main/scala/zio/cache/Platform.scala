package zio.cache

import java.util.{HashMap, Map}

private object Platform {

  /**
   * Constructs a new concurrent map.=
   */
  def newConcurrentMap[K, V]: Map[K, V] =
    new HashMap[K, V]()
}
