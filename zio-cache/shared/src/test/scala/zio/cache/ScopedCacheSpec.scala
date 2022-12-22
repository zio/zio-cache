package zio.cache

import zio.test.Assertion._
import zio.test._
import zio.{Clock, Duration, Exit, IO, Ref, Schedule, Scope, UIO, ZEnvironment, ZIO, duration2DurationOps, durationInt}

object ScopedCacheSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment with Scope, Any] = suite("SharedScoped")(
    test("cacheStats should correctly keep track of cache size, hits and misses") {
      check(Gen.int) { salt =>
        val capacity = 100
        val scopedCache =
          ScopedCache.make(
            capacity = capacity,
            timeToLive = Duration.Infinity,
            lookup = ScopedLookup((key: Int) => ZIO.succeed(hash(salt)(key)))
          )
        ZIO.scoped {
          scopedCache.flatMap { cache =>
            for {
              _          <- ZIO.foreachParDiscard((1 to capacity).map(_ / 2))(n => ZIO.scoped(cache.get(n).zipRight(ZIO.unit)))
              cacheStats <- cache.cacheStats
            } yield assertTrue(cacheStats == CacheStats(hits = 49, misses = 51, size = 51))
          }
        }
      }
    },
    test("invalidate should properly remove and clean a resource from the cache") {
      val capacity = 100
      for {
        observablesResource <- ZIO.foreach((0 until capacity).toList)(_ => ObservableResourceForTest.makeUnit)
        scopedCache =
          ScopedCache.make(
            capacity = capacity,
            timeToLive = Duration.Infinity,
            lookup = ScopedLookup((key: Int) => observablesResource(key).managed)
          )
        result <- ZIO.scoped {
                    scopedCache.flatMap { cache =>
                      for {
                        _                    <- ZIO.foreachParDiscard((0 until capacity))(n => ZIO.scoped(cache.get(n).zipRight(ZIO.unit)))
                        _                    <- cache.invalidate(42)
                        cacheContainsKey42   <- cache.contains(42)
                        cacheStats           <- cache.cacheStats
                        key42ResourceCleaned <- observablesResource(42).assertAcquiredOnceAndCleaned
                        allOtherStillNotCleaned <-
                          ZIO.foreach(observablesResource.zipWithIndex.filterNot(_._2 == 42).map(_._1))(
                            _.assertAcquiredOnceAndNotCleaned
                          )
                      } yield assertTrue(
                        cacheStats == CacheStats(hits = 0, misses = 100, size = 99),
                        !(cacheContainsKey42)
                      ) && key42ResourceCleaned && allOtherStillNotCleaned.reduce(_ && _)
                    }
                  }
      } yield result
    },
    test("invalidate should not invalidate anything before effect is evaluated") {
      for {
        observablesResource <- ObservableResourceForTest.makeUnit
        scopedCache =
          ScopedCache.make(
            capacity = 4,
            timeToLive = Duration.Infinity,
            lookup = ScopedLookup((_: Unit) => observablesResource.managed)
          )
        result <-
          ZIO.scoped {
            scopedCache.flatMap { cache =>
              for {
                _                                  <- ZIO.scoped(cache.get(key = ()).zipRight(ZIO.unit))
                invalidateEffect                    = cache.invalidate(key = ())
                cacheContainsKey42BeforeInvalidate <- cache.contains(key = ())
                resourceNotCleanedBeforeInvalidate <- observablesResource.assertAcquiredOnceAndNotCleaned
                _                                  <- ZIO.scoped(cache.get(()).zipRight(ZIO.unit))
                _                                  <- invalidateEffect
                cacheContainsKey42AfterInvalidate  <- cache.contains(key = ())
                resourceCleanedAfterInvalidate     <- observablesResource.assertAcquiredOnceAndCleaned
              } yield assertTrue(
                cacheContainsKey42BeforeInvalidate,
                !(cacheContainsKey42AfterInvalidate)
              ) && resourceNotCleanedBeforeInvalidate && resourceCleanedAfterInvalidate
            }
          }
      } yield result
    },
    test("invalidateAll should properly remove and clean all resource from the cache") {
      val capacity = 100
      for {
        observablesResource <- ZIO.foreach((0 until capacity).toList)(_ => ObservableResourceForTest.makeUnit)
        scopedCache =
          ScopedCache.make(
            capacity,
            Duration.Infinity,
            ScopedLookup((key: Int) => observablesResource(key).managed)
          )
        result <- ZIO.scoped {
                    scopedCache.flatMap { cache =>
                      for {
                        _          <- ZIO.foreachParDiscard((0 until capacity))(n => ZIO.scoped(cache.get(n).zipRight(ZIO.unit)))
                        _          <- cache.invalidateAll
                        contains   <- ZIO.foreachPar((0 to capacity).toList)(cache.contains(_))
                        cacheStats <- cache.cacheStats
                        allCleaned <- ZIO.foreach(observablesResource)(_.assertAcquiredOnceAndCleaned)
                      } yield assertTrue(cacheStats == CacheStats(hits = 0, misses = 100, size = 0)) && assert(
                        contains
                      )(forall(isFalse)) &&
                        allCleaned.reduce(_ && _)
                    }
                  }
      } yield result
    },
    suite(".get")(
      test("should not put anything in the cache before returned managed by get is used") {
        for {
          subResource <- ObservableResourceForTest.makeUnit
          scopedCache = ScopedCache.make(
                          capacity = 1,
                          timeToLive = 60.second,
                          lookup = ScopedLookup((_: Unit) => subResource.managed)
                        )
          checkInside <-
            ZIO.scoped {
              scopedCache.flatMap { cache =>
                for {
                  notAcquiredBeforeAnything                 <- subResource.assertNotAcquired
                  _                                          = cache.get(key = ())
                  notAcquireYetAfterGettingManagedFromCache <- subResource.assertNotAcquired
                  keyPresent                                <- cache.contains(key = ())
                } yield notAcquiredBeforeAnything && assertTrue(
                  !(keyPresent)
                ) && notAcquireYetAfterGettingManagedFromCache
              }
            }
        } yield checkInside
      },
      test("when use sequentially, should properly call correct lookup") {
        check(Gen.int) { salt =>
          val scopedCache =
            ScopedCache.make(100, Duration.Infinity, ScopedLookup((key: Int) => ZIO.succeed(hash(salt)(key))))
          ZIO.scoped {
            scopedCache.flatMap { cache =>
              for {
                actual  <- ZIO.foreach(1 to 100)(n => ZIO.scoped(cache.get(n).flatMap(ZIO.succeed(_))))
                expected = (1 to 100).map(hash(salt))
              } yield assertTrue(actual == expected)
            }
          }
        }
      },
      test("when use concurrent, should properly call correct lookup") {
        check(Gen.int) { salt =>
          val scopedCache =
            ScopedCache.make(100, Duration.Infinity, ScopedLookup((key: Int) => ZIO.succeed(hash(salt)(key))))
          ZIO.scoped {
            scopedCache.flatMap { cache =>
              for {
                actual  <- ZIO.foreachPar(1 to 100)(n => ZIO.scoped(cache.get(n).flatMap(ZIO.succeed(_))))
                expected = (1 to 100).map(hash(salt))
              } yield assertTrue(actual == expected)
            }
          }
        }
      },
      test(".get should clean and remove old resource to respect cache capacity") {
        check(Gen.int) { salt =>
          val scopedCache =
            ScopedCache.make(10, Duration.Infinity, ScopedLookup((key: Int) => ZIO.succeed(hash(salt)(key))))
          ZIO.scoped {
            scopedCache.flatMap { cache =>
              for {
                actual     <- ZIO.foreach(1 to 100)(n => ZIO.scoped(cache.get(n).flatMap(ZIO.succeed(_))))
                expected    = (1 to 100).map(hash(salt))
                cacheStats <- cache.cacheStats
              } yield assertTrue(
                actual == expected,
                cacheStats.size == 10
              )
            }
          }
        }
      },
      test("sequential use on managed returned by a single call to .get should create only one resource") {
        for {
          subResource <- ObservableResourceForTest.makeUnit
          scopedCache = ScopedCache.make(
                          capacity = 1,
                          timeToLive = 60.second,
                          lookup = ScopedLookup((_: Unit) => subResource.managed)
                        )
          checkInside <-
            ZIO.scoped {
              scopedCache.flatMap { cache =>
                for {
                  notAcquiredBeforeAnything                 <- subResource.assertNotAcquired
                  resourceManagedProxy                       = cache.get(key = ())
                  notAcquireYetAfterGettingManagedFromCache <- subResource.assertNotAcquired
                  _                                         <- ZIO.scoped(resourceManagedProxy)
                  acquireOnceAfterUse                       <- subResource.assertAcquiredOnceAndNotCleaned
                  _                                         <- ZIO.scoped(resourceManagedProxy)
                  acquireOnceAfterSecondUse                 <- subResource.assertAcquiredOnceAndNotCleaned
                } yield notAcquiredBeforeAnything && notAcquireYetAfterGettingManagedFromCache && acquireOnceAfterUse && acquireOnceAfterSecondUse
              }
            }
          finallyCleaned <- subResource.assertAcquiredOnceAndCleaned
        } yield checkInside && finallyCleaned
      },
      test("sequentially use of .get should create only one resource") {
        for {
          subResource <- ObservableResourceForTest.makeUnit
          scopedCache = ScopedCache.make(
                          capacity = 1,
                          timeToLive = 60.second,
                          lookup = ScopedLookup((_: Unit) => subResource.managed)
                        )
          checkInside <- ZIO.scoped {
                           scopedCache.flatMap { cache =>
                             for {
                               notAcquiredBeforeAnything <- subResource.assertNotAcquired
                               _                         <- ZIO.scoped(cache.get(key = ()))
                               acquireOnceAfterUse       <- subResource.assertAcquiredOnceAndNotCleaned
                               _                         <- ZIO.scoped(cache.get(key = ()))
                               acquireOnceAfterSecondUse <- subResource.assertAcquiredOnceAndNotCleaned
                             } yield notAcquiredBeforeAnything && acquireOnceAfterUse && acquireOnceAfterSecondUse
                           }
                         }
          finallyCleaned <- subResource.assertAcquiredOnceAndCleaned
        } yield checkInside && finallyCleaned
      },
      test("sequential use on failing managed should cache the error and immediately call the resource finalizer") {
        for {
          watchableLookup <-
            WatchableLookup.makeZIO[Unit, Throwable, Nothing](_ => ZIO.fail(new RuntimeException("fail")))
          scopedCache = ScopedCache.make(
                          capacity = 1,
                          timeToLive = 60.second,
                          lookup = ScopedLookup(watchableLookup.lookup)
                        )
          testResult <-
            ZIO.scoped {
              scopedCache.flatMap { cache =>
                for {
                  notAcquiredBeforeAnything                 <- watchableLookup.assertCalledNum(key = ())(equalTo(0))
                  resourceManagedProxy                       = cache.get(key = ())
                  notAcquireYetAfterGettingManagedFromCache <- watchableLookup.assertCalledNum(key = ())(equalTo(0))
                  _                                         <- ZIO.scoped(resourceManagedProxy).either
                  acquireAndCleanedRightAway                <- watchableLookup.assertAllCleanedForKey(())
                  _                                         <- ZIO.scoped(resourceManagedProxy).either
                  didNotTryToCreateAnOtherResource          <- watchableLookup.assertCalledNum(key = ())(equalTo(1))
                } yield notAcquiredBeforeAnything && notAcquireYetAfterGettingManagedFromCache && acquireAndCleanedRightAway && didNotTryToCreateAnOtherResource
              }
            }
        } yield testResult
      },
      test("concurrent use on managed returned by a single call to .get should create only one resource") {
        for {
          subResource <- ObservableResourceForTest.makeUnit
          scopedCache = ScopedCache.make(
                          capacity = 1,
                          timeToLive = 60.second,
                          lookup = ScopedLookup((_: Unit) => subResource.managed)
                        )
          checkInside <-
            ZIO.scoped {
              scopedCache.flatMap { cache =>
                val scoped = cache.get(key = ())
                for {
                  scope1                             <- Scope.make
                  scope2                             <- Scope.make
                  acquire1                            = scoped.provideEnvironment(ZEnvironment(scope1))
                  release1                            = scope1.close(_)
                  acquire2                            = scoped.provideEnvironment(ZEnvironment(scope2))
                  release2                            = scope2.close(_)
                  acquisition                        <- subResource.assertNotAcquired
                  _                                  <- acquire2
                  acquireOnceAfterFirstUse           <- subResource.assertAcquiredOnceAndNotCleaned
                  _                                  <- acquire1
                  acquireOnceAfterSecondUse          <- subResource.assertAcquiredOnceAndNotCleaned
                  _                                  <- release2(Exit.unit)
                  _                                  <- release1(Exit.unit)
                  stillNotCleanedForPotentialNextUse <- subResource.assertAcquiredOnceAndNotCleaned
                } yield acquisition && acquireOnceAfterFirstUse && acquireOnceAfterSecondUse && stillNotCleanedForPotentialNextUse
              }
            }
          finallyCleaned <- subResource.assertAcquiredOnceAndCleaned
        } yield checkInside && finallyCleaned
      },
      test("concurrent use on failing managed should cache the error and immediately call the resource finalizer") {
        for {
          watchableLookup <-
            WatchableLookup.makeZIO[Unit, Throwable, Nothing](_ => ZIO.fail(new RuntimeException("fail")))
          scopedCache = ScopedCache.make(
                          capacity = 1,
                          timeToLive = 60.second,
                          lookup = ScopedLookup(watchableLookup.lookup)
                        )
          testResult <-
            ZIO.scoped {
              scopedCache.flatMap { cache =>
                for {
                  notAcquiredBeforeAnything                 <- watchableLookup.assertCalledNum(key = ())(equalTo(0))
                  resourceManagedProxy                       = cache.get(key = ())
                  notAcquireYetAfterGettingManagedFromCache <- watchableLookup.assertCalledNum(key = ())(equalTo(0))
                  _                                         <- ZIO.scoped(resourceManagedProxy).either <&> ZIO.scoped(resourceManagedProxy).either
                  acquireAndCleanedRightAway                <- watchableLookup.assertAllCleanedForKey(())
                  didNotTryToCreateAnOtherResource          <- watchableLookup.assertCalledNum(key = ())(equalTo(1))
                } yield notAcquiredBeforeAnything && notAcquireYetAfterGettingManagedFromCache && acquireAndCleanedRightAway && didNotTryToCreateAnOtherResource
              }
            }
        } yield testResult
      },
      test(
        "when two managed returned by two .get call live longer than the cache, the real created subresource should be cleaned only use it's not in use anymore"
      ) {
        for {
          subResource <- ObservableResourceForTest.makeUnit
          scopedCache  = ScopedCache.make(1, 60.second, ScopedLookup((_: Unit) => subResource.managed))
          scope1      <- Scope.make
          scope2      <- Scope.make
          releases <- ZIO.scoped {
                        scopedCache.flatMap { cache =>
                          for {
                            _ <- cache.get(key = ()).provideEnvironment(ZEnvironment(scope1))
                            _ <- cache.get(key = ()).provideEnvironment(ZEnvironment(scope2))
                          } yield (scope1.close(_), scope2.close(_))
                        }
                      }
          (release1, release2)    = releases
          afterSharedManagerLife <- subResource.assertAcquiredOnceAndNotCleaned
          _                      <- release1(Exit.unit)
          afterFirstSubClean     <- subResource.assertAcquiredOnceAndNotCleaned
          _                      <- release2(Exit.unit)
          afterSecondSubClean    <- subResource.assertAcquiredOnceAndCleaned
        } yield afterSharedManagerLife && afterFirstSubClean && afterSecondSubClean
      },
      test(
        "when two managed obtained by a single managed returned by a single .get call live longer than the cache, the real created subresource should be cleaned only use it's not in use anymore"
      ) {
        for {
          subResource <- ObservableResourceForTest.makeUnit
          scopedCache  = ScopedCache.make(1, 60.second, ScopedLookup((_: Unit) => subResource.managed))
          scope1      <- Scope.make
          scope2      <- Scope.make
          releases <- ZIO.scoped {
                        scopedCache.flatMap { cache =>
                          val manager = cache.get(key = ())
                          for {
                            _ <- manager.provideEnvironment(ZEnvironment(scope1))
                            _ <- manager.provideEnvironment(ZEnvironment(scope2))
                          } yield (scope1.close(_), scope2.close(_))
                        }
                      }
          (release1, release2)    = releases
          afterSharedManagerLife <- subResource.assertAcquiredOnceAndNotCleaned
          _                      <- release1(Exit.unit)
          afterFirstSubClean     <- subResource.assertAcquiredOnceAndNotCleaned
          _                      <- release2(Exit.unit)
          afterSecondSubClean    <- subResource.assertAcquiredOnceAndCleaned
        } yield afterSharedManagerLife && afterFirstSubClean && afterSecondSubClean
      },
      test("should clean old resource if cache size is exceeded") {
        val genTestInput = for {
          cacheSize     <- Gen.int(1, 5)
          numCreatedKey <- Gen.int(cacheSize, cacheSize + 3)
        } yield (cacheSize, numCreatedKey)
        check(genTestInput) { case (cacheSize, numCreatedKey) =>
          for {
            watchableLookup <- WatchableLookup.make[Int, Unit]((_: Int) => ())
            scopedCache      = ScopedCache.make(cacheSize, 60.second, ScopedLookup(watchableLookup.lookup))
            testResult <-
              ZIO.scoped {
                scopedCache.flatMap { cache =>
                  for {
                    _ <- ZIO.foreachDiscard((0 until numCreatedKey).toList) { key =>
                           ZIO.scoped(cache.get(key).unit)
                         }
                    createdResource <- watchableLookup.createdResources
                    oldestResourceCleaned <-
                      assertAllM(
                        (0 until numCreatedKey - cacheSize).flatMap(createdResource).map(_.assertAcquiredOnceAndCleaned)
                      )
                    newestResourceNotCleanedYet <- assertAllM(
                                                     (numCreatedKey - cacheSize until numCreatedKey)
                                                       .flatMap(createdResource)
                                                       .map(_.assertAcquiredOnceAndNotCleaned)
                                                   )
                  } yield oldestResourceCleaned && newestResourceNotCleanedYet
                }
              }
          } yield testResult
        }
      }
    ),
    suite("`refresh` method")(
      test("should update the cache with a new value") {
        def inc(n: Int) = n * 10

        def retrieve(multiplier: Ref[Int])(key: Int) =
          multiplier
            .updateAndGet(inc)
            .map(key * _)

        val seed = 1
        val key  = 123
        for {
          ref <- Ref.make(seed)
          scopedCache = ScopedCache.make(
                          1,
                          Duration.Infinity,
                          ScopedLookup((key: Int) => retrieve(ref)(key))
                        )
          result <- ZIO.scoped {
                      scopedCache.flatMap { cache =>
                        for {
                          val1 <- ZIO.scoped(cache.get(key))
                          _    <- cache.refresh(key)
                          val2 <- ZIO.scoped(cache.get(key))
                          val3 <- ZIO.scoped(cache.get(key))
                        } yield assert(val2)(equalTo(val3) && equalTo(inc(val1)))
                      }
                    }
        } yield result
      },
      test("should clean old resource when making a new one") {
        for {
          watchableLookup <- WatchableLookup.makeUnit
          _               <- ZIO.unit
          scopedCache = ScopedCache.make(
                          1,
                          Duration.Infinity,
                          ScopedLookup(watchableLookup.lookup)
                        )
          result <- ZIO.scoped {
                      scopedCache.flatMap { cache =>
                        for {
                          _                        <- ZIO.scoped(cache.get(key = ()))
                          _                        <- cache.refresh(key = ())
                          createdResources         <- watchableLookup.createdResources.map(_.apply(key = ()))
                          firstResourceCleaned     <- createdResources.head.assertAcquiredOnceAndCleaned
                          secondResourceNotCleaned <- createdResources(1).assertAcquiredOnceAndNotCleaned
                        } yield firstResourceCleaned && secondResourceNotCleaned
                      }
                    }
        } yield result
      },
      test("should update the cache with a new value even if the last `get` or `refresh` failed") {
        val error = new RuntimeException("Must be a multiple of 3")

        def inc(n: Int) = n + 1

        def retrieve(number: Ref[Int])(key: Int) =
          number
            .updateAndGet(inc)
            .flatMap {
              case n if n % 3 == 0 =>
                ZIO.fail(error)
              case n =>
                ZIO.succeed(key * n)
            }

        val seed = 2
        val key  = 1
        for {
          ref <- Ref.make(seed)
          scopedCache = ScopedCache.make(
                          capacity = 1,
                          timeToLive = Duration.Infinity,
                          lookup = ScopedLookup((key: Int) => retrieve(ref)(key))
                        )
          result <- ZIO.scoped {
                      scopedCache.flatMap { cache =>
                        for {
                          failure1 <- ZIO.scoped(cache.get(key)).either
                          _        <- cache.refresh(key)
                          val1     <- ZIO.scoped(cache.get(key)).either
                          _        <- cache.refresh(key)
                          failure2 <- cache.refresh(key).either
                          _        <- cache.refresh(key)
                          val2     <- ZIO.scoped(cache.get(key)).either
                        } yield assert(failure1)(isLeft(equalTo(error))) &&
                          assert(failure2)(isLeft(equalTo(error))) &&
                          assert(val1)(isRight(equalTo(4))) &&
                          assert(val2)(isRight(equalTo(7)))
                      }
                    }
        } yield result
      },
      test("should create and acquire subresource if the key doesn't exist in the cache") {
        val capacity    = 100
        val scopedCache = ScopedCache.make(capacity, Duration.Infinity, ScopedLookup((_: Int) => ZIO.unit))
        ZIO.scoped {
          scopedCache.flatMap { cache =>
            for {
              count0 <- cache.size
              _      <- ZIO.foreachDiscard(1 to capacity)(cache.refresh(_))
              count1 <- cache.size
            } yield assertTrue(
              count0 == 0,
              count1 == capacity
            )
          }
        }
      },
      test("should clean old resource if cache size is exceeded") {
        val genTestInput = for {
          cacheSize     <- Gen.int(1, 5)
          numCreatedKey <- Gen.int(cacheSize, cacheSize + 3)
        } yield (cacheSize, numCreatedKey)
        check(genTestInput) { case (cacheSize, numCreatedKey) =>
          for {
            watchableLookup <- WatchableLookup.make[Int, Unit]((_: Int) => ())
            scopedCache      = ScopedCache.make(cacheSize, 60.second, ScopedLookup(watchableLookup.lookup))
            testResult <-
              ZIO.scoped {
                scopedCache.flatMap { cache =>
                  for {
                    _ <- ZIO.foreachDiscard((0 until numCreatedKey).toList) { key =>
                           cache.refresh(key)
                         }
                    createdResource <- watchableLookup.createdResources
                    oldestResourceCleaned <-
                      assertAllM(
                        (0 until numCreatedKey - cacheSize).flatMap(createdResource).map(_.assertAcquiredOnceAndCleaned)
                      )
                    newestResourceNotCleanedYet <- assertAllM(
                                                     (numCreatedKey - cacheSize until numCreatedKey)
                                                       .flatMap(createdResource)
                                                       .map(_.assertAcquiredOnceAndNotCleaned)
                                                   )
                  } yield oldestResourceCleaned && newestResourceNotCleanedYet
                }
              }
          } yield testResult
        }
      }
    ),
    suite("expiration")(
      suite("get")(
        test(
          "managed returned by .get should recall lookup function if resource is too old and release the previous resource"
        ) {
          for {
            watchableLookup <- WatchableLookup.makeUnit
            fakeClock       <- MockedJavaClock.make
            result <-
              ZIO.scoped {
                ScopedCache
                  .makeWith(capacity = 10, scopedLookup = ScopedLookup(watchableLookup.lookup), clock = fakeClock) {
                    (_: Exit[Nothing, Unit]) =>
                      10.second
                  }
                  .flatMap { (scopedCache: ScopedCache[Unit, Any, Nothing, Unit]) =>
                    val subManaged = scopedCache.get(())
                    for {
                      _                               <- ZIO.scoped(subManaged.unit)
                      _                               <- fakeClock.advance(5.second)
                      _                               <- ZIO.scoped(subManaged.unit)
                      oneResourceCreatedAfter5second  <- watchableLookup.assertCalledNum(key = ())(equalTo(1))
                      _                               <- fakeClock.advance(4.second)
                      _                               <- ZIO.scoped(subManaged.unit)
                      oneResourceCreatedAfter9second  <- watchableLookup.assertCalledNum(key = ())(equalTo(1))
                      _                               <- fakeClock.advance(2.second)
                      _                               <- ZIO.scoped(subManaged.unit)
                      twoResourceCreatedAfter11second <- watchableLookup.assertCalledNum(key = ())(equalTo(2))
                      previousResourceCleaned         <- watchableLookup.assertFirstNCreatedResourceCleaned(key = (), 1)
                    } yield oneResourceCreatedAfter5second && oneResourceCreatedAfter9second && twoResourceCreatedAfter11second && previousResourceCleaned
                  }
              }
          } yield result
        },
        test(
          "get should recall lookup function if resource is too old and release old resource (when using multiple time the managed given by .get)"
        ) {
          for {
            watchableLookup <- WatchableLookup.makeUnit
            fakeClock       <- MockedJavaClock.make
            result <- ZIO.scoped {
                        ScopedCache
                          .makeWith(10, ScopedLookup(watchableLookup.lookup), fakeClock) { (_: Exit[Nothing, Unit]) =>
                            10.second
                          }
                          .flatMap { (scopedCache: ScopedCache[Unit, Any, Nothing, Unit]) =>
                            val useGetManaged = ZIO.scoped(scopedCache.get(key = ()).unit)
                            for {
                              _             <- useGetManaged
                              _             <- fakeClock.advance(5.second)
                              _             <- useGetManaged
                              after5second  <- watchableLookup.assertCalledNum(key = ())(equalTo(1))
                              _             <- fakeClock.advance(4.second)
                              _             <- useGetManaged
                              after9second  <- watchableLookup.assertCalledNum(key = ())(equalTo(1))
                              _             <- fakeClock.advance(2.second)
                              _             <- useGetManaged
                              after11second <- watchableLookup.assertCalledNum(key = ())(equalTo(2))
                              previousResourcesCleaned <-
                                watchableLookup.assertFirstNCreatedResourceCleaned(key = (), 1)
                            } yield after5second && after9second && after11second && previousResourcesCleaned
                          }
                      }
          } yield result
        },
        test(
          "when resource get expired but still used it should wait until resource is not cleaned anymore to clean immediately"
        ) {
          for {
            watchableLookup <- WatchableLookup.makeUnit
            fakeClock       <- MockedJavaClock.make
            result <- ZIO.scoped {
                        ScopedCache
                          .makeWith(10, ScopedLookup(watchableLookup.lookup), fakeClock) { (_: Exit[Nothing, Unit]) =>
                            10.second
                          }
                          .flatMap { (scopedCache: ScopedCache[Unit, Any, Nothing, Unit]) =>
                            for {
                              scope                           <- Scope.make
                              acquire                          = scopedCache.get(()).provideEnvironment(ZEnvironment(scope))
                              release                          = scope.close(_)
                              _                               <- acquire
                              _                               <- fakeClock.advance(11.second)
                              _                               <- ZIO.scoped(scopedCache.get(()).unit)
                              twoResourcesCreated             <- watchableLookup.assertCalledNum(key = ())(equalTo(2))
                              firstCreatedResource            <- watchableLookup.firstCreatedResource(key = ())
                              notCleanedBeforeItFinishToBeUse <- firstCreatedResource.assertAcquiredOnceAndNotCleaned
                              _                               <- release(Exit.unit)
                              finallyCleanedAfterItsUsed      <- firstCreatedResource.assertAcquiredOnceAndCleaned
                            } yield twoResourcesCreated && notCleanedBeforeItFinishToBeUse && finallyCleanedAfterItsUsed
                          }
                      }
          } yield result
        }
      ),
      suite("refresh")(
        test("should not clean the resource if it's not yet expired until the new resource is ready") {
          for {
            watchableLookup <- WatchableLookup.makeUnit
            fakeClock       <- MockedJavaClock.make
            result <- ZIO.scoped {
                        ScopedCache
                          .makeWith(10, ScopedLookup(watchableLookup.lookup), fakeClock) { (_: Exit[Nothing, Unit]) =>
                            10.second
                          }
                          .flatMap { (scopedCache: ScopedCache[Unit, Any, Nothing, Unit]) =>
                            for {
                              _            <- ZIO.scoped(scopedCache.get(key = ()).unit)
                              _            <- fakeClock.advance(9.second)
                              _            <- watchableLookup.lock
                              refreshFiber <- scopedCache.refresh(key = ()).fork
                              _ <-
                                watchableLookup
                                  .getCalledNum(key = ())
                                  .repeat(
                                    (Schedule.recurWhile[Int](_ < 1) >>> Schedule.elapsed).whileOutput(_ < 100.millis)
                                  )
                              _                          <- Live.live(ZIO.sleep(100.millis))
                              secondLookupCalled         <- watchableLookup.assertCalledNum(key = ())(equalTo(2))
                              firstCreatedResource       <- watchableLookup.firstCreatedResource(key = ())
                              firstResourceNotYetCleaned <- firstCreatedResource.assertAcquiredOnceAndNotCleaned
                              _                          <- watchableLookup.unlock
                              _                          <- refreshFiber.join
                              firsResourceFinallyCleaned <- firstCreatedResource.assertAcquiredOnceAndCleaned
                            } yield secondLookupCalled && firstResourceNotYetCleaned && firsResourceFinallyCleaned
                          }
                      }
          } yield result
        },
        test("should clean the resource if it's expired and not in used") {
          for {
            watchableLookup <- WatchableLookup.makeUnit
            fakeClock       <- MockedJavaClock.make
            result <- ZIO.scoped {
                        ScopedCache
                          .makeWith(10, ScopedLookup(watchableLookup.lookup), fakeClock) { (_: Exit[Nothing, Unit]) =>
                            10.second
                          }
                          .flatMap { (scopedCache: ScopedCache[Unit, Any, Nothing, Unit]) =>
                            for {
                              _            <- ZIO.scoped(scopedCache.get(key = ()).unit)
                              _            <- fakeClock.advance(11.second)
                              _            <- watchableLookup.lock
                              refreshFiber <- scopedCache.refresh(key = ()).fork
                              _ <-
                                watchableLookup
                                  .getCalledNum(key = ())
                                  .repeat(
                                    (Schedule.recurWhile[Int](_ < 1) >>> Schedule.elapsed).whileOutput(_ < 100.millis)
                                  )
                              _                    <- Live.live(ZIO.sleep(100.millis))
                              secondLookupCalled   <- watchableLookup.assertCalledNum(key = ())(equalTo(2))
                              firstResourceCleaned <- watchableLookup.assertFirstNCreatedResourceCleaned(key = (), 1)
                              _                    <- watchableLookup.unlock
                              _                    <- refreshFiber.join
                            } yield secondLookupCalled && firstResourceCleaned
                          }
                      }
          } yield result
        },
        test("should wait to clean expired resource until it's not in use anymore") {
          for {
            watchableLookup <- WatchableLookup.makeUnit
            fakeClock       <- MockedJavaClock.make
            result <- ZIO.scoped {
                        ScopedCache
                          .makeWith(10, ScopedLookup(watchableLookup.lookup), fakeClock) { (_: Exit[Nothing, Unit]) =>
                            10.second
                          }
                          .flatMap { (scopedCache: ScopedCache[Unit, Any, Nothing, Unit]) =>
                            for {
                              scope                      <- Scope.make
                              acquire                     = scopedCache.get(key = ()).provideEnvironment(ZEnvironment(scope))
                              release                     = scope.close(_)
                              _                          <- acquire
                              _                          <- fakeClock.advance(11.second)
                              _                          <- scopedCache.refresh(key = ())
                              secondLookupCalled         <- watchableLookup.assertCalledNum(key = ())(equalTo(2))
                              firstCreatedResource       <- watchableLookup.firstCreatedResource(key = ())
                              firstResourceNotYetCleaned <- firstCreatedResource.assertAcquiredOnceAndNotCleaned
                              _                          <- release(Exit.unit)
                              firsResourceFinallyCleaned <- firstCreatedResource.assertAcquiredOnceAndCleaned
                            } yield secondLookupCalled && firstResourceNotYetCleaned && firsResourceFinallyCleaned
                          }
                      }
          } yield result
        }
      )
    ),
    suite("property base testing")(
      test(
        "after any suite of balanced resource use, cleaning the cache should release all underlying resources"
      ) {
        import PropertyBaseTestingUtil._
        check(balancedSequenceOfAcquireReleaseAndRefresh, Gen.int(1, 20)) { (resourceOperations, cacheSize) =>
          for {
            watchableLookup <- WatchableLookup.make[Key, Unit]((_: Key) => ())
            releasers <-
              ZIO.scoped {
                ScopedCache
                  .makeWith(cacheSize, scopedLookup = ScopedLookup(watchableLookup.lookup)) {
                    (_: Exit[Nothing, Unit]) =>
                      10.second
                  }
                  .flatMap(applyResourceOperations(_, resourceOperations))
              }
            allCleaned <- watchableLookup.assertAllCleaned
          } yield allCleaned && assertTrue(releasers.isEmpty)
        }
      },
      test(
        "after any suite of resource use, cleaning the cache should only resource not in used, others should be cleaned after there are not used anymore"
      ) {
        import PropertyBaseTestingUtil._
        check(sequenceOfAcquireReleaseAndRefreshLettingSomeResourceUsed, Gen.int(1, 20)) {
          case (ResourceOperationsAndResult(resourceOperations, resourceCleaned, resourceNotCleaned), cacheSize) =>
            for {
              watchableLookup <- WatchableLookup.make[Key, Unit]((_: Key) => ())
              notUsedReleasers <-
                ZIO.scoped {
                  ScopedCache
                    .make(capacity = cacheSize, timeToLive = 10.second, lookup = ScopedLookup(watchableLookup.lookup))
                    .flatMap(applyResourceOperations(_, resourceOperations))
                }
              allResourceNotInUseAnymoreCleaned <-
                assertAllM(resourceCleaned.map(watchableLookup.assertAllCleanedForKey))

              allResourceInUseAnymoreNotCleaned <-
                assertAllM(resourceNotCleaned.map(watchableLookup.assertAtLeastOneResourceNotCleanedForKey))
              _                                            <- ZIO.foreachDiscard(notUsedReleasers.values)(_.apply(Exit.unit))
              allCleanedAfterAllResourceAreNotInUseAnymore <- watchableLookup.assertAllCleaned
            } yield allResourceNotInUseAnymoreCleaned && allResourceInUseAnymoreNotCleaned && allCleanedAfterAllResourceAreNotInUseAnymore
        }
      },
      test(
        "after any suite of balanced resource use, cleaning the cache should release all underlying resources even if some resource acquisition did fail"
      ) {
        import PropertyBaseTestingUtil._
        case class TestInput(
          operations: List[ResourceOperation],
          cacheSize: Int,
          sequenceOfFailureOrSuccess: List[Boolean]
        )
        val genTestInput = for {
          operations                 <- balancedSequenceOfAcquireReleaseAndRefresh
          cacheSize                  <- Gen.int(1, 10)
          sequenceOfFailureOrSuccess <- Gen.listOfN(operations.length)(Gen.boolean)
        } yield TestInput(operations, cacheSize, sequenceOfFailureOrSuccess)

        check(genTestInput) { case TestInput(resourceOperations, cacheSize, sequenceOfFailureOrSuccess) =>
          for {
            lookupCallNum <- Ref.make(0)
            watchableLookup <- WatchableLookup.makeZIO[Key, Throwable, Unit] { (_: Key) =>
                                 lookupCallNum.getAndUpdate(_ + 1).flatMap { index =>
                                   ZIO.fail(new RuntimeException("fail")).unless(sequenceOfFailureOrSuccess(index)).unit
                                 }
                               }
            releasers <-
              ZIO.scoped {
                ScopedCache
                  .make(capacity = cacheSize, timeToLive = 10.second, lookup = ScopedLookup(watchableLookup.lookup))
                  .flatMap(applyResourceOperations(_, resourceOperations, ignoreCacheError = true))
              }
            allCleaned <- watchableLookup.assertAllCleaned
          } yield allCleaned && assert(releasers)(isEmpty)
        }
      }
    )
  )

  type Releaser = (=> Exit[Any, Any]) => UIO[Unit]

  sealed trait ObservableResourceForTest[E, V] {
    def assertNotAcquired: UIO[TestResult]

    def assertAcquiredOnceAndNotCleaned: UIO[TestResult]

    def assertAcquiredOnceAndCleaned: UIO[TestResult]

    def managed: ZIO[Scope, E, V]
  }

  object ObservableResourceForTest {
    def makeUnit: UIO[ObservableResourceForTest[Nothing, Unit]] = make(value = ())

    def make[V](value: V): UIO[ObservableResourceForTest[Nothing, V]] = makeZIO(ZIO.succeed(value))

    def makeZIO[E, V](effect: IO[E, V]): UIO[ObservableResourceForTest[E, V]] = for {
      resourceAcquisitionCount     <- Ref.make(0)
      resourceAcquisitionReleasing <- Ref.make(0)
      getState                      = resourceAcquisitionCount.get <*> resourceAcquisitionReleasing.get
    } yield new ObservableResourceForTest[E, V] {

      override def assertNotAcquired: UIO[TestResult] = getState.map { case (numAcquisition, numCleaned) =>
        assert(numAcquisition)(equalTo(0).label(s"Resource acquired when it should not have")) &&
          assert(numCleaned)(equalTo(0).label(s"Resource cleaned when it should not have"))
      }

      override def assertAcquiredOnceAndNotCleaned: UIO[TestResult] = getState.map {
        case (numAcquisition, numCleaned) =>
          assert(numAcquisition)(equalTo(1).label(s"Resource not acquired once")) &&
            assert(numCleaned)(equalTo(0).label(s"Resource cleaned when it should not have"))
      }

      override def assertAcquiredOnceAndCleaned: UIO[TestResult] = getState.map { case (numAcquisition, numCleaned) =>
        assert(numAcquisition)(equalTo(1).label(s"Resource not acquired once")) &&
          assert(numCleaned)(equalTo(1).label(s"Resource not cleaned when it should have"))
      }

      override def managed: ZIO[Scope, E, V] =
        ZIO.uninterruptibleMask { restore =>
          for {
            parent <- ZIO.scope
            child  <- parent.fork
            _      <- resourceAcquisitionCount.update(_ + 1)
            _      <- child.addFinalizer(resourceAcquisitionReleasing.update(_ + 1))
            value  <- ZIO.acquireReleaseInterruptibleExit(restore(effect))(child.close(_))
          } yield value
        }
    }
  }

  sealed trait WatchableLookup[K, E, V] {
    def lookup(key: K): ZIO[Scope, E, V]

    def createdResources: UIO[Map[K, List[ObservableResourceForTest[E, V]]]]

    def assertCalledNum(key: K)(sizeAssertion: Assertion[Int]): UIO[TestResult] =
      getCalledNum(key).map(value => assert(value)(sizeAssertion))

    def getCalledNum(key: K): UIO[Int] = createdResources.map(_.get(key).fold(ifEmpty = 0)(_.length))

    def assertFirstNCreatedResourceCleaned(key: K, num: Int): UIO[TestResult] =
      createdResources.flatMap { resources =>
        resourcesCleaned(resources.getOrElse(key, List.empty).take(num))
      }

    private def resourcesCleaned(resources: Iterable[ObservableResourceForTest[E, V]]): UIO[TestResult] =
      assertAllM(resources.map(_.assertAcquiredOnceAndCleaned))

    def assertAllCleaned: UIO[TestResult] = createdResources.flatMap { resources =>
      resourcesCleaned(resources.values.flatten)
    }

    def assertAllCleanedForKey(key: K): UIO[TestResult] = createdResources.flatMap { resources =>
      resourcesCleaned(resources.getOrElse(key, List.empty))
    }

    def assertAtLeastOneResourceNotCleanedForKey(key: K): UIO[TestResult] = createdResources.flatMap { resources =>
      assertOneOfM(resources.getOrElse(key, List.empty).map(_.assertAcquiredOnceAndNotCleaned))
    }

    def lock: UIO[Unit]

    def unlock: UIO[Unit]

    def firstCreatedResource(key: K): UIO[ObservableResourceForTest[E, V]] =
      createdResources.map(_.apply(key = key).head)
  }

  object WatchableLookup {
    def makeZIO[K, E, V](concreteLookup: K => IO[E, V]): UIO[WatchableLookup[K, E, V]] = for {
      blocked   <- Ref.make(false)
      resources <- Ref.make(Map.empty[K, List[ObservableResourceForTest[E, V]]])
    } yield new WatchableLookup[K, E, V] {
      override def lookup(key: K): ZIO[Scope, E, V] = (for {
        observableResource <- ObservableResourceForTest.makeZIO(concreteLookup(key))
        _ <- resources.update { resourceMap =>
               val newResource = resourceMap.get(key).getOrElse(List.empty) ++ List(observableResource)
               resourceMap.updated(key, newResource)
             }
        _ <- blocked.get
               .repeat(Schedule.recurWhile[Boolean](identity) && Schedule.exponential(10.millis, 2.0))
               .withClock(Clock.ClockLive)
      } yield observableResource.managed).flatten

      override val createdResources: UIO[Map[K, List[ObservableResourceForTest[E, V]]]] = resources.get

      override val lock   = blocked.set(true)
      override val unlock = blocked.set(false)
    }

    def make[K, V](concreteLookup: K => V): UIO[WatchableLookup[K, Nothing, V]] = makeZIO(
      concreteLookup.andThen(ZIO.succeed(_))
    )

    def makeUnit: UIO[WatchableLookup[Unit, Nothing, Unit]] = make((_: Unit) => ())
  }

  private def hash(x: Int): Int => Int =
    y => (x ^ y).hashCode

  object PropertyBaseTestingUtil {
    type Key              = Char
    type ResourceIdForKey = Int
    sealed trait ResourceOperation
    case class ResourceId(key: Key, resourceIdForKey: ResourceIdForKey)
    case class Acquire(id: ResourceId) extends ResourceOperation
    case class Release(id: ResourceId) extends ResourceOperation
    case class Refresh(key: Key)       extends ResourceOperation
    case class Invalidate(key: Key)    extends ResourceOperation

    case class ResourceOperationsAndResult(
      operations: List[ResourceOperation],
      keyWithCleanedResource: Set[Key],
      keyWithUncleanedResource: Set[Key]
    )

    private def sequenceOfAcquireReleaseAndRefreshRec(
      previousResourceOperation: List[ResourceOperation],
      allKey: Set[Key],
      notOpenYet: Set[ResourceId],
      openedButNotCleaned: Set[ResourceId],
      acceptResourceNotCleaned: Boolean
    ): Gen[Any, ResourceOperationsAndResult] = if (
      notOpenYet.isEmpty && (openedButNotCleaned.isEmpty || acceptResourceNotCleaned)
    ) {
      val keyWithUncleanedResource = openedButNotCleaned.map(_.key)
      Gen.const(
        ResourceOperationsAndResult(
          operations = previousResourceOperation,
          keyWithCleanedResource = allKey -- keyWithUncleanedResource,
          keyWithUncleanedResource = keyWithUncleanedResource
        )
      )
    } else {
      val acquireOrRelease =
        Gen.elements[ResourceOperation](
          (notOpenYet.toList.map(Acquire.apply) ++ openedButNotCleaned.toList.map(Release.apply)): _*
        )
      val refresh = Gen.elements[ResourceOperation](allKey.map(Refresh.apply).toList: _*)
      val invalidatePresent = Gen.elements[ResourceOperation](openedButNotCleaned.map { totalId =>
        Invalidate(totalId.key)
      }.toList: _*)
      val invalidateNotPresent =
        Gen.elements[ResourceOperation]((allKey -- openedButNotCleaned.map(_.key)).map(Invalidate.apply).toList: _*)
      for {
        nextOp <-
          Gen.weighted((acquireOrRelease, 8.0), (refresh, 2.0), (invalidatePresent, 2.0), (invalidateNotPresent, 1.0))
        (newOpened, newNotOpenYet) = nextOp match {
                                       case Acquire(id)   => (notOpenYet - id, openedButNotCleaned + id)
                                       case Release(id)   => (notOpenYet, openedButNotCleaned - id)
                                       case Refresh(_)    => (notOpenYet, openedButNotCleaned)
                                       case Invalidate(_) => (notOpenYet, openedButNotCleaned)
                                     }
        result <-
          sequenceOfAcquireReleaseAndRefreshRec(
            previousResourceOperation = previousResourceOperation ++ List(nextOp),
            allKey = allKey,
            notOpenYet = newOpened,
            openedButNotCleaned = newNotOpenYet,
            acceptResourceNotCleaned = acceptResourceNotCleaned
          )
      } yield result
    }

    val balancedSequenceOfAcquireReleaseAndRefresh: Gen[Sized, List[ResourceOperation]] = {
      val someKey                   = Gen.alphaChar
      val numResourcesCreatedPerKey = Gen.int(1, 10)
      Gen.mapOf(someKey, numResourcesCreatedPerKey).flatMap { numPairByKey =>
        sequenceOfAcquireReleaseAndRefreshRec(
          previousResourceOperation = List.empty,
          allKey = numPairByKey.keySet,
          notOpenYet = numPairByKey.flatMap { case (key, numPair) => (1 to numPair).map(ResourceId(key, _)) }.toSet,
          openedButNotCleaned = Set.empty,
          acceptResourceNotCleaned = false
        ).map(_.operations)
      }
    }

    val sequenceOfAcquireReleaseAndRefreshLettingSomeResourceUsed: Gen[Sized, ResourceOperationsAndResult] = {
      val someKey                   = Gen.alphaChar
      val numResourcesCreatedPerKey = Gen.int(1, 10)
      Gen.mapOf(someKey, numResourcesCreatedPerKey).flatMap { numPairByKey =>
        sequenceOfAcquireReleaseAndRefreshRec(
          previousResourceOperation = List.empty,
          allKey = numPairByKey.keySet,
          notOpenYet = numPairByKey.flatMap { case (key, numPair) => (1 to numPair).map(ResourceId(key, _)) }.toSet,
          openedButNotCleaned = Set.empty,
          acceptResourceNotCleaned = true
        )
      }
    }

    def applyResourceOperations[V](
      scopedCache: ScopedCache[Key, Any, Throwable, V],
      resourceOperations: List[ResourceOperation],
      ignoreCacheError: Boolean = false
    ): IO[TestFailure[Nothing], Map[ResourceId, Releaser]] =
      for {
        notUsedReleasers <-
          ZIO.foldLeft(resourceOperations)(Map.empty[ResourceId, Releaser]) { (releasers, resourceOperation) =>
            resourceOperation match {
              case Acquire(totalId @ ResourceId(key, _)) =>
                Scope.make.flatMap { scope =>
                  scopedCache
                    .get(key)
                    .provideEnvironment(ZEnvironment(scope))
                    .as {
                      val releaser: Releaser = scope.close(_)
                      releasers.updated(totalId, releaser)
                    }
                    .catchAll { error =>
                      if (ignoreCacheError) {
                        ZIO.succeed(releasers)
                      } else {
                        ZIO.fail(TestFailure.die(error))
                      }
                    }
                }
              case Release(totalId) =>
                releasers.get(totalId) match {
                  case None =>
                    ZIO
                      .fail(TestFailure.die(new RuntimeException("release before acquire")))
                      .unless(ignoreCacheError)
                      .as(releasers)
                  case Some(releaser) =>
                    releaser(Exit.unit).as(releasers - totalId)
                }
              case Refresh(key) =>
                scopedCache
                  .refresh(key)
                  .catchAll { error =>
                    ZIO.fail(TestFailure.die(error)).unless(ignoreCacheError)
                  }
                  .as(releasers)
              case Invalidate(key) =>
                scopedCache
                  .invalidate(key)
                  .as(releasers)
            }
          }
      } yield notUsedReleasers
  }

  def assertAllM(results: Iterable[UIO[TestResult]]): UIO[TestResult] =
    ZIO.foldLeft(results)(assertCompletes)((l, r) => r.map(l && _))

  def assertOneOfM(results: Iterable[UIO[TestResult]]): UIO[TestResult] =
    ZIO.foldLeft(results)(!assertCompletes)((l, r) => r.map(l || _))
}
