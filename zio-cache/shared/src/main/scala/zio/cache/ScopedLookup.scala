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

import zio.{Scope, ZIO}

/**
 * A `ScopedLookup` represents a lookup function for a [[ScopedCache]]: given a
 * key, it returns a `ZIO` effect that acquires a resource in a `Scope` and
 * produces a value of type `Value`, or fails with an error of type `Error`,
 * using an environment of type `Environment`. The cache closes that scope when
 * it no longer needs the resource.
 *
 * It is the scoped counterpart of [[Lookup]].
 */
final case class ScopedLookup[-Key, -Environment, +Error, +Value](
  lookup: Key => ZIO[Environment with Scope, Error, Value]
) extends (Key => ZIO[Environment with Scope, Error, Value]) {

  /**
   * Computes a value for the specified key in a `Scope`, or fails with an error.
   */
  def apply(key: Key): ZIO[Environment with Scope, Error, Value] =
    lookup(key)
}
