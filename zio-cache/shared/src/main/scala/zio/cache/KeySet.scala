package zio.cache

/**
 * A `KeySet` is a sorted set of keys in the cache ordered by last access.
 * For efficiency, the set is implemented in terms of a doubly linked list
 * and is not safe for concurrent access.
 */
private[cache] final class KeySet[Key] {
  private[this] var head: MapKey[Key] = null
  private[this] var tail: MapKey[Key] = null

  /**
   * Adds the specified key to the set.
   */
  def add(key: MapKey[Key]): Unit =
    if (key ne tail) {
      if (tail ne null) {
        val previous = key.previous
        val next     = key.next
        if (next ne null) {
          key.next = null
          if (previous ne null) {
            previous.next = next
            next.previous = previous
          } else {
            head = next
            head.previous = null
          }
        }
        tail.next = key
        key.previous = tail
        tail = key
      } else {
        head = key
        tail = key
      }
    }

  /**
   * Removes the lowest priority key from the set.
   */
  def remove(): MapKey[Key] = {
    val key = head
    if (key ne null) {
      val next = key.next
      if (next ne null) {
        key.next = null
        head = next
        head.previous = null
      } else {
        head = null
        tail = null
      }
    }
    key
  }
}
