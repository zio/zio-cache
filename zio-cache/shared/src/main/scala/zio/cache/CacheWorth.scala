package zio.cache

sealed trait CacheWorth { self =>
  import CacheWorth._

  def ++(that: => CacheWorth): CacheWorth =
    if (self == Equal) that else self

  def flip: CacheWorth =
    if (self == Left) Right else if (self == Right) Left else Equal
}
object CacheWorth {
  case object Left  extends CacheWorth
  case object Right extends CacheWorth
  case object Equal extends CacheWorth
}
