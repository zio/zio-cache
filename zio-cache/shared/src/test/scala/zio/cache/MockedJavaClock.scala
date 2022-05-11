package zio.cache

import zio.duration.Duration
import zio.{Ref, Runtime, UIO}

import java.time.{Clock, Instant, ZoneId}

abstract class MockedJavaClock extends Clock {
  def advance(duration: Duration): UIO[Unit]
}

object MockedJavaClock {
  def make: UIO[MockedJavaClock] = for {
    ref <- Ref.make(Instant.now())
  } yield new MockedJavaClock {
    override def getZone: ZoneId = ???
    override def instant(): Instant = Runtime.default.unsafeRun {
      ref.get
    }

    override def withZone(zone: ZoneId): Clock          = ???
    override def advance(duration: Duration): UIO[Unit] = ref.update(_.plusNanos(duration.toNanos))
  }
}
