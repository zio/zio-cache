---
id: index
title: "Introduction to ZIO Cache"
sidebar_label: "ZIO Cache"
---

ZIO Cache is a library that makes it easy to optimize the performance of our application by caching values.

@PROJECT_BADGES@

## Introduction

Sometimes we may call or receive requests to do overlapping work. Assume we are writing a service that is going to handle all incoming requests. We don't want to handle duplicate requests. Using ZIO Cache we can make our application to be more **performant** by preventing duplicated works.

Some key features of ZIO Cache:

- **Compositionality** — If we want our applications to be **compositional**, different parts of our application may do overlapping work. ZIO Cache helps us to stay benefit from compositionality while using caching.

- **Unification of Synchronous and Asynchronous Caches** — Compositional definition of cache in terms of _lookup function_ unifies synchronous and asynchronous caches. So the lookup function can compute value either synchronously or asynchronously.

- **Deep ZIO Integration** — ZIO Cache is a ZIO native solution. So without losing the power of ZIO it includes support for _concurrent lookups_, _failure_, and _interruption_.

- **Capacity and Time to Live** — A cache holds at most a fixed number of values. When it is full, it removes the least recently accessed value. Each value also expires after a time to live, and that time can depend on whether the lookup succeeded or failed.

- **Cache/Entry Statistics** — ZIO Cache tracks hits, misses, and size for the whole cache, and the time each entry was loaded. So we can look at how our cache is doing and decide whether its capacity and time to live need to change.

## How to Define a Cache?

A cache is defined in terms of a lookup function that describes how to compute the value associated with a key if a value is not already in the cache.

```scala mdoc:compile-only
import zio._
import zio.cache.Lookup

val lookup: Lookup[String, Any, Nothing, Int] =
  Lookup(key => ZIO.succeed(key.length))
```

The lookup function takes a key of type `Key` and returns a `ZIO` effect that requires an environment of type `Environment` and can fail with an error of type `Error` or succeed with a value of type `Value`. Because the lookup function returns a `ZIO` effect it can describe both synchronous and asynchronous workflows.

We construct a cache using a lookup function as well as a maximum size and a time to live.

```scala mdoc:compile-only
import zio._
import zio.cache.{Cache, Lookup}

val cache: UIO[Cache[String, Nothing, Int]] =
  Cache.make(
    capacity = 100,
    timeToLive = Duration.Infinity,
    lookup = Lookup(key => ZIO.succeed(key.length))
  )
```

Once we have created a cache the most idiomatic way to work with it is the `get` operator. The `get` operator will return the current value in the cache if it exists or else compute a new value, put it in the cache, and return it.

If multiple concurrent processes get the value at the same time the value will only be computed once, with all of the other processes receiving the computed value as soon as it is available. All of this will be done using ZIO's fiber based concurrency model without ever blocking any underlying operating system threads.

## Installation

In order to use this library, we need to add the following line in our `build.sbt` file:

```scala
libraryDependencies += "dev.zio" %% "zio-cache" % "@VERSION@"
```

## Example

In this example, we are calling `timeConsumingEffect` three times in parallel with the same key. The ZIO Cache runs this effect only once. So the concurrent lookups will suspend until the value being computed is available:

```scala mdoc:compile-only
import zio._
import zio.cache.{Cache, Lookup}

object ZIOCacheExample extends ZIOAppDefault {
  def timeConsumingEffect(key: String) =
    ZIO.sleep(5.seconds).as(key.hashCode)

  def run =
    for {
      cache <- Cache.make(
        capacity = 100,
        timeToLive = Duration.Infinity,
        lookup = Lookup(timeConsumingEffect)
      )
      result <- cache
        .get("key1")
        .zipPar(cache.get("key1"))
        .zipPar(cache.get("key1"))
      _ <- ZIO.debug(
        s"Result of parallel execution of three effects with the same key: $result"
      )

      hits <- cache.cacheStats.map(_.hits)
      misses <- cache.cacheStats.map(_.misses)
      _ <- ZIO.debug(s"Number of cache hits: $hits")
      _ <- ZIO.debug(s"Number of cache misses: $misses")
    } yield ()

}
```

The output of this program should be as follows:

```
Result of parallel execution of three effects with the same key: ((3288498,3288498),3288498)
Number of cache hits: 2
Number of cache misses: 1
```

## Resources

- [Compositional Caching](https://www.youtube.com/watch?v=iFeTUhYpPLs) by Adam Fraser (December 2020) — In this talk, Adam will introduce ZIO Cache, a new library in the ZIO ecosystem that provides a drop-in caching solution for ZIO applications. We will see how ZIO’s support for asynchrony and concurrent lets us implement a cache in terms of a single lookup function and how we get many other things such as typed errors and compositional caching policies for free. See how easy it can be to add caching to your ZIO application!

