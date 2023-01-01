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
 * Like lookup but scoped version
 */
final case class ScopedLookup[-Key, -Environment, +Error, +Value](
  lookup: Key => ZIO[Environment with Scope, Error, Value]
) extends (Key => ZIO[Environment with Scope, Error, Value]) {

  /**
   * Computes a value for the specified key or fails with an error.
   */
  def apply(key: Key): ZIO[Environment with Scope, Error, Value] =
    lookup(key)
}
