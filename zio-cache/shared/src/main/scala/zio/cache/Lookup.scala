/*
 * Copyright 2020-2023 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.cache

import zio.{Chunk, ZIO}

/**
 * A `Lookup` represents a lookup function that, given a key of type `Key`, can
 * return a `ZIO` effect that will either produce a value of type `Value` or
 * fail with an error of type `Error` using an environment of type
 * `Environment`.
 *
 * You can think of a `Lookup` as an effectual function that computes a value
 * given a key. Given any effectual function you can convert it to a lookup
 * function for a cache by using the `Lookup` constructor.
 */
sealed trait Lookup[-Key, -Environment, +Error, +Value] extends (Key => ZIO[Environment, Error, Value]) {

  /**
   * Computes a value for the specified key or fails with an error.
   */
  def lookup(key: Key): ZIO[Environment, Error, Value]

  /**
   * Computes a value for the specified keys or fails with an error.
   */
  def lookupAll(keys: Iterable[Key]): ZIO[Environment, Error, Chunk[Value]]

  def apply(key: Key): ZIO[Environment, Error, Value] =
    lookup(key)
}

object Lookup {

  /**
   * Constructs a lookup from a function to look up a single key.
   */
  def apply[Key, Environment, Error, Value](
    f: Key => ZIO[Environment, Error, Value]
  ): Lookup[Key, Environment, Error, Value] =
    full(f, keys => ZIO.foreach(Chunk.fromIterable(keys))(f))

  /**
   * Constructs a lookup from a function to look up a batch of keys.
   */
  def batched[Key, Environment, Error, Value](
    f: Iterable[Key] => ZIO[Environment, Error, Chunk[Value]]
  ): Lookup[Key, Environment, Error, Value] =
    full(key => f(Chunk.single(key)).map(_.head), f)

  /**
   * Constructs a lookup from separate functions to look up a single key and
   * a batch of keys.
   */
  def full[Key, Environment, Error, Value](
    lookup0: Key => ZIO[Environment, Error, Value],
    lookupAll0: Iterable[Key] => ZIO[Environment, Error, Chunk[Value]]
  ): Lookup[Key, Environment, Error, Value] =
    new Lookup[Key, Environment, Error, Value] {
      def lookup(key: Key): ZIO[Environment, Error, Value] =
        lookup0(key)
      def lookupAll(keys: Iterable[Key]): ZIO[Environment, Error, Chunk[Value]] =
        lookupAll0(keys)
    }
}
