/*
 * Copyright 2020-2023 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
