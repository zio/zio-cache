package zio.cache

import zio.{Duration, Exit, Trace, UIO, ZIO}

/**
 *  Provides a computation to determine the minimum duration between two cache lookup function evaluations
 *  for a particular key
 */
case class TimeToLive[Error, Value](compute: Exit[Error, Value] => UIO[Duration])
object TimeToLive {
  def fixed[Error, Value](duration: Duration)(implicit trace: Trace) = TimeToLive { _: Exit[Error, Value] =>
    ZIO.succeed(duration)
  }
  def infinity[Error, Value](implicit trace: Trace) = fixed(Duration.Infinity)
  def jittered[Error, Value](duration: Duration)(implicit trace: Trace): TimeToLive[Error, Value] =
    jittered(0.8, 1.2, duration)
  def jittered[Error, Value](min: Double, max: Double, duration: Duration)(implicit trace: Trace) = TimeToLive {
    _: Exit[Error, Value] =>
      zio.Random.nextDouble.map { random =>
        val d        = duration.toNanos
        val jittered = d * min * (1 - random) + d * max * random
        Duration.fromNanos(jittered.toLong)
      }
  }
}
