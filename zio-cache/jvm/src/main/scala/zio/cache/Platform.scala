package zio.cache

import java.util.Map
import java.util.concurrent.ConcurrentHashMap

private object Platform {

  def newConcurrentMap[K, V]: Map[K, V] =
    new ConcurrentHashMap[K, V]()
}
