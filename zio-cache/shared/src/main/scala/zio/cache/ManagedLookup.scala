package zio.cache

import zio.ZManaged

/**
 * Like lookup but managed version
 */
final case class ManagedLookup[-Key, -Environment, +Error, +Value](lookup: Key => ZManaged[Environment, Error, Value])
    extends (Key => ZManaged[Environment, Error, Value]) {

  /**
   * Computes a value for the specified key or fails with an error.
   */
  def apply(key: Key): ZManaged[Environment, Error, Value] =
    lookup(key)
}
