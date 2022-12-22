package zio.cache

import zio.ZIO

/**
 * A `Lookup` represents a lookup function that, given a key of type `Key`, can
 * return a `ZIO` effect that will either produce a value of type `Value` or
 * fail with an error of type `Error` using an environment of type
 * `Environment`.
 *
 * You can think of a `Lookup` as an effectual function that computes a value
 * given a key. Given any effectual function you can convert it to a lookup
 * function for a cache by using the `Lookup` constructor.
 */
final case class Lookup[-Key, -R, +E, +A](lookup: Key => ZIO[R, E, A]) extends (Key => ZIO[R, E, A]) {

  /**
   * Computes a value for the specified key or fails with an error.
   */
  def apply(key: Key): ZIO[R, E, A] = lookup(key)
}
