---
id: lookup
title: "Lookup"
---

A `Lookup` is a lookup function that, given a key of type `Key`, knows how to compute a value of type `Value`, requiring an environment of type `Environment` and potentially failing with an error of type `Error`.

```scala mdoc
import zio._

trait Lookup[-Key, -R, +E, +A] {
  def lookup(key: Key): ZIO[R, E, A]
}
```

You can think of a key as essentially an effectual function to compute a value. And in fact you can construct a `Lookup` from any effectual function using the `apply` method on `Lookup`.

```scala mdoc
object Lookup {

  def apply[Key, R, E, A](
    f: Key => ZIO[R, E, A]
  ): Lookup[Key, R, E, A] =
    ???
}
```

Since the lookup function can return a `ZIO` effect it can either return its result synchronously or asynchronously. It can also use an environment and potentially fail with an error.

In short, if you can describe it with a ZIO effect you can use it as the lookup function for a cache.
