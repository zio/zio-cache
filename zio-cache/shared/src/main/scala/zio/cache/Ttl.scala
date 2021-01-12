package zio.cache

import zio.duration._

sealed abstract class Ttl[-Key, -Value] { self =>

  def ttl(key: Key, entry: Entry[Value]): Option[Duration]
}

object Ttl {

  def apply[Key, Value](ttl0: (Key, Entry[Value]) => Option[Duration]): Ttl[Key, Value] =
    new Ttl[Key, Value] {
      override def ttl(key: Key, entry: Entry[Value]): Option[Duration] = ttl0(key, entry)
    }

  val none: Ttl[Any, Any] = Ttl((_, _) => None)

  val never: Ttl[Any, Any] = none
}
