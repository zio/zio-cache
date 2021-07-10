package zio.cache

import zio.{Chunk, ZIO}

/**
 * A `Lookup` represnts a lookup function that, given a key of type `Key`, can
 * return a `ZIO` effect that will either produce a value of type `Value` or
 * fail with an error of type `Error` using an environment of type
 * `Environment`.
 *
 * You can think of a `Lookup` as an effectual function that computes a value
 * given a key. Given any effectual function you can convert it to a lookup
 * function for a cache by using the `Lookup` constructor.
 */
final class Lookup[-Key, -Environment, +Error, +Value] private (
  private val lookup: Iterable[Key] => ZIO[Environment, Error, Chunk[Value]]
) extends (Key => ZIO[Environment, Error, Value]) {

  /**
   * Computes a value for the specified key or fails with an error.
   */
  def apply(key: Key): ZIO[Environment, Error, Value] =
    lookup(Chunk(key)).map(_.head)

  /**
   * Computes values for the specified keys or fails with an error.
   */
  def apply(keys: Iterable[Key]): ZIO[Environment, Error, Chunk[Value]] =
    lookup(keys)
}

object Lookup {

  /**
   * Constructs a `Lookup` from an effectual function.
   */
  def apply[Key, Environment, Error, Value](
    lookup: Key => ZIO[Environment, Error, Value]
  ): Lookup[Key, Environment, Error, Value] =
    fromFunctionZIO(lookup)

  /**
   * Constructs a `Lookup` from an effectual function that computes a single
   * value for the specified key.
   */
  def fromFunctionZIO[Key, Environment, Error, Value](
    lookup: Key => ZIO[Environment, Error, Value]
  ): Lookup[Key, Environment, Error, Value] =
    fromFunctionManyZIO(keys => ZIO.foreach(Chunk.fromIterable(keys))(lookup))

  /**
   * Constructs a `Lookup` from an effectual function that returns a colletion
   * of values for a collection of keys. The effectual function must return
   * exactly one value for each key in the same order as the keys are provided.
   */
  def fromFunctionManyZIO[Key, Environment, Error, Value](
    lookup: Iterable[Key] => ZIO[Environment, Error, Chunk[Value]]
  ): Lookup[Key, Environment, Error, Value] =
    new Lookup(lookup)
}
