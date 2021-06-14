package zio.cache

import com.github.ghik.silencer.silent

import java.util.{HashMap, Map}

@silent("never used")
private object Platform {

  /**
   * Constructs a new concurrent map.=
   */
  def newConcurrentMap[K, V]: Map[K, V] =
    new HashMap[K, V]()
}
