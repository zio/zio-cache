package zio.cache

import zio.{Scope, ZIO}

/**
 * Like lookup but scoped version
 */
final case class ScopedLookup[-Key, -Environment, +Error, +Value](
  lookup: Key => ZIO[Environment with Scope, Error, Value]
) extends (Key => ZIO[Environment with Scope, Error, Value]) {

  /**
   * Computes a value for the specified key or fails with an error.
   */
  def apply(key: Key): ZIO[Environment with Scope, Error, Value] =
    lookup(key)
}
