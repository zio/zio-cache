package zio.cache

/**
 * A `MapKey` represents a key in the cache. It contains mutable references
 * to the previous key and next key in the `KeySet` to support an efficient
 * implementation of a sorted set of keys.
 */
private[cache] final class MapKey[Key](
  val value: Key,
  var previous: MapKey[Key] = null,
  var next: MapKey[Key] = null
)
