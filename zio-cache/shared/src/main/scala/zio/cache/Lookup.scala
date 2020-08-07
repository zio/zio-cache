package zio.cache

import zio.ZIO

/**
 * A function that can lookup a value given a key, or possibly fail while trying.
 */
final case class Lookup[-Key, -R, +E, +Value](value: Key => ZIO[R, E, Value]) extends (Key => ZIO[R, E, Value]) {
  def apply(v1: Key): ZIO[R, E, Value] = value(v1)
}
