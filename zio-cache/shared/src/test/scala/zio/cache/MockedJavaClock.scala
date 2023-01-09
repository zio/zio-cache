package zio.cache

import zio.{Duration, Ref, Runtime, UIO, Unsafe}

import java.time.{Clock, Instant, ZoneId}

abstract class MockedJavaClock extends Clock {
  def advance(duration: Duration): UIO[Unit]
}

object MockedJavaClock {
  def make: UIO[MockedJavaClock] = for {
    ref <- Ref.make(Instant.now())
  } yield new MockedJavaClock {
    override def getZone: ZoneId = ???
    override def instant(): Instant =
      Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run {
          ref.get
        }.getOrThrowFiberFailure()
      }

    override def withZone(zone: ZoneId): Clock          = ???
    override def advance(duration: Duration): UIO[Unit] = ref.update(_.plusNanos(duration.toNanos))
  }
}
