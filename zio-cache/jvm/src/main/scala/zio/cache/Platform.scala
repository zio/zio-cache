package zio.cache

import com.github.ghik.silencer.silent

import java.util.Map
import java.util.concurrent.ConcurrentHashMap

@silent("never used")
private object Platform {

  /**
   * Constructs a new concurrent map.=
   */
  def newConcurrentMap[K, V]: Map[K, V] =
    new ConcurrentHashMap[K, V]()
}
