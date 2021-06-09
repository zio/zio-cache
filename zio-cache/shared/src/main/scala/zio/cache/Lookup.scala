package zio.cache

import zio.ZIO

final case class Lookup[-Key, -Environment, +Error, +Value](lookup: Key => ZIO[Environment, Error, Value])
    extends (Key => ZIO[Environment, Error, Value]) {

  def apply(key: Key): ZIO[Environment, Error, Value] =
    lookup(key)
}
