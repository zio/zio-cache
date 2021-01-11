package zio.cache

import zio._

/**
 * A function that can lookup a value given a key, or possibly fail while trying.
 */
final case class Lookup[-Key, -R, +E, +Value](
  lookup: Key => ZIO[R, E, Value],
  include: Key => Lookup.Include
) extends (Key => ZIO[R, E, Value]) { self =>
  import Lookup._

  /**
   * Looks up the value for the given key.
   */
  def apply(key: Key): ZIO[R, E, Value] =
    lookup(key)

  /**
   * Returns a new lookup function that will always cache keys matching the
   * specified predicate.
   */
  def excludeKeys[Key1 <: Key](f: Key1 => Boolean): Lookup[Key1, R, E, Value] =
    new Lookup(lookup, key => if (f(key)) Include.Never else include(key))

  /**
   * Returns a new lookup function that will never cache keys matching the
   * specified predicate.
   */
  def includeKeys[Key1 <: Key](f: Key1 => Boolean): Lookup[Key1, R, E, Value] =
    new Lookup(lookup, key => if (f(key)) Include.Always else include(key))

  /**
   * Returns a new lookup function that performs the function `f` every time a
   * value is successfully looked up.
   */
  def onSuccess[R1 <: R](f: Value => URIO[R1, Any]): Lookup[Key, R1, E, Value] =
    Lookup(key => lookup(key).tap(f))

  /**
   * Returns a new lookup function that performs the function `f` every time a
   * failure occurs while looking up a value.
   */
  def onFailure[R1 <: R](f: E => URIO[R1, Any]): Lookup[Key, R1, E, Value] =
    Lookup(key => lookup(key).tapError(f))

  /**
   * Combines this lookup function with the specified lookup function to
   * return a new lookup function that tries to lookup the value for a given
   * key using this lookup function, and if this lookup function fails tries
   * to lookup the value for the given key using that lookup function.
   */
  def orElse[Key1 <: Key, R1 <: R, E2, Value1 >: Value](
    that: => Lookup[Key1, R1, E2, Value1]
  ): Lookup[Key1, R1, E2, Value1] =
    Lookup(key => self.lookup(key).orElse(that.lookup(key)))

  /**
   * Combines this lookup function with the specified lookup function to
   * return a new lookup function that tries to lookup the value for a given
   * key using both this lookup function and that lookup function, returning
   * the value from the first one to succeed and safely interrupting the
   * other.
   */
  def race[Key1 <: Key, R1 <: R, E1 >: E, Value1 >: Value](
    that: Lookup[Key1, R1, E1, Value1]
  ): Lookup[Key1, R1, E1, Value1] =
    Lookup(key => self.lookup(key).race(that.lookup(key)))
}

object Lookup {

  def apply[Key, R, E, Value](lookup: Key => ZIO[R, E, Value]): Lookup[Key, R, E, Value] =
    new Lookup(lookup, _ => Include.Sometimes)

  sealed trait Include

  object Include {
    case object Always    extends Include
    case object Never     extends Include
    case object Sometimes extends Include
  }
}
