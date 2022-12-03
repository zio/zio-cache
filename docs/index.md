---
id: index
title: "Introduction to ZIO Cache"
sidebar_label: "ZIO Cache"
---

ZIO Cache makes it easy to cache values to optimize your application's performance.

A cache is defined in terms of a lookup function that describes how to compute the value associated with a key if a value is not already in the cache.

```scala mdoc
import zio._

trait Lookup[-Key, -Environment, +Error, +Value] {
  def lookup(key: Key): ZIO[Environment, Error, Value]
}
```

The lookup function takes a key of type `Key` and returns a `ZIO` effect that requires an environment of type `Environment` and can fail with an error of type `Error` or succeed with a value of type `Value`. Because the lookup function returns a `ZIO` effect it can describe both synchronous and asynchronous workflows.

We construct a cache using a lookup function as well as a maximum size and a time to live.

```scala mdoc
trait Cache[-Key, +Error, +Value] {
  def get(k: Key): IO[Error, Value]
}

object Cache {

  def make[Key, Environment, Error, Value](
    capacity: Int,
    timeToLive: Duration,
    lookup: Lookup[Key, Environment, Error, Value]
  ): ZIO[Environment, Nothing, Cache[Key, Error, Value]] =
    ???
}
```

Once we have created a cache the most idiomatic way to work with it is the `get` operator. The `get` operator will return the current value in the cache if it exists or else compute a new value, put it in the cache, and return it.

If multiple concurrent processes get the value at the same time the value will only be computed once, with all of the other processes receiving the computed value as soon as it is available. All of this will be done using ZIO's fiber based concurrency model without ever blocking any underlying operating system threads.

## Installation

Include ZIO Cache in your project by adding the following to your `build.sbt` file:

```scala
libraryDependencies += "dev.zio" %% "zio-cache" % "@VERSION@" 
```
