package zio.cache

import zio.Chunk

sealed trait InvalidationEvent extends Product with Serializable
object InvalidationEvent {

  final case class SingleInvalidation[Key](key: Key) extends InvalidationEvent

  final case class OverCapacityInvalidation[Key](keys: Chunk[Key]) extends InvalidationEvent

  final case class InvalidationAll(count: Int) extends InvalidationEvent
}
