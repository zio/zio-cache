package zio.cache

import zio.{Scope, ZIO}

/**
 * Like lookup but scoped version
 */
final case class ScopedLookup[-Key, -R, +E, +A](lookup: Key => ZIO[R with Scope, E, A])
    extends (Key => ZIO[R with Scope, E, A]) {

  /**
   * Computes a value for the specified key or fails with an error.
   */
  def apply(key: Key): ZIO[R with Scope, E, A] = lookup(key)
}
