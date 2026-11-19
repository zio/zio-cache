---
id: index
title: "Introduction to ZIO Cache"
sidebar_label: "Introduction"
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

## Installation

ZIO Cache is published for Scala 2.12, 2.13 and 3. Add the artifact that matches your platform to your `build.sbt`.

On the JVM:

```scala
libraryDependencies ++= Seq(
  // Cache, ScopedCache, Lookup and statistics (JVM)
  "dev.zio" %% "zio-cache" % "@VERSION@"
)
```

On Scala.js or Scala Native, use `%%%` instead:

```scala
libraryDependencies ++= Seq(
  // Cache, ScopedCache, Lookup and statistics (Scala.js and Scala Native)
  "dev.zio" %%% "zio-cache" % "@VERSION@"
)
```

## Learn More

Start with [Getting Started](./getting-started.md), then build your first cache step by step in the [tutorial](./tutorial-first-cache.md). To make specific decisions, read the how-to guides, starting with [How to Choose Capacity and Time to Live](./how-to-choose-capacity-and-ttl.md). The reference pages describe [Cache](./cache.md), [ScopedCache](./scoped-cache.md), [Lookup](./lookup.md), and the statistics types. Talks and articles are listed under [Resources](./resources.md).
