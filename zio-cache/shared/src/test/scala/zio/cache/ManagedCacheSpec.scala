package zio.cache

import zio._
import zio.clock.Clock
import zio.duration._
import zio.random.Random
import zio.test.Assertion._
import zio.test.TestAspect.samples
import zio.test._

object ManagedCacheSpec extends DefaultRunnableSpec {
  override def spec: ZSpec[Environment, Any] = suite("SharedManaged")(
    testM("cacheStats should correctly keep track of cache size, hits and misses") {
      checkM(Gen.anyInt) { salt =>
        val capacity = 100
        val managedCache =
          ManagedCache.make(
            capacity = capacity,
            timeToLive = Duration.Infinity,
            lookup = ManagedLookup { key: Int => Managed.succeed(hash(salt)(key)) }
          )
        managedCache.use { cache =>
          for {
            _          <- ZIO.foreachPar_((1 to capacity).map(_ / 2))(cache.get(_).use_(ZIO.unit))
            cacheStats <- cache.cacheStats
          } yield assert(cacheStats)(equalTo(CacheStats(hits = 49, misses = 51, size = 51)))
        }
      }
    },
    testM("invalidate should properly remove and clean a resource from the cache") {
      val capacity = 100
      for {
        observablesResource <- ZIO.foreach((0 until capacity).toList)(_ => ObservableResourceForTest.makeUnit)
        managedCache =
          ManagedCache.make(
            capacity = capacity,
            timeToLive = Duration.Infinity,
            lookup = ManagedLookup { key: Int => observablesResource(key).managed }
          )
        result <-
          managedCache.use { cache =>
            for {
              _                    <- ZIO.foreachPar_((0 until capacity))(cache.get(_).use_(ZIO.unit))
              _                    <- cache.invalidate(42)
              cacheContainsKey42   <- cache.contains(42)
              cacheStats           <- cache.cacheStats
              key42ResourceCleaned <- observablesResource(42).assertAcquiredOnceAndCleaned
              allOtherStillNotCleaned <-
                ZIO.foreach(observablesResource.zipWithIndex.filterNot(_._2 == 42).map(_._1))(
                  _.assertAcquiredOnceAndNotCleaned
                )
            } yield assert(cacheStats)(equalTo(CacheStats(hits = 0, misses = 100, size = 99))) && assert(
              cacheContainsKey42
            )(
              isFalse
            ) && key42ResourceCleaned && allOtherStillNotCleaned.reduce(_ && _)
          }
      } yield result
    },
    testM("invalidateAll should properly remove and clean all resource from the cache") {
      val capacity = 100
      for {
        observablesResource <- ZIO.foreach((0 until capacity).toList)(_ => ObservableResourceForTest.makeUnit)
        managedCache =
          ManagedCache.make(capacity, Duration.Infinity, ManagedLookup { key: Int => observablesResource(key).managed })
        result <- managedCache.use { cache =>
                    for {
                      _          <- ZIO.foreachPar_((0 until capacity))(cache.get(_).use_(ZIO.unit))
                      _          <- cache.invalidateAll
                      contains   <- ZIO.foreachPar(0 to capacity)(cache.contains(_))
                      cacheStats <- cache.cacheStats
                      allCleaned <- ZIO.foreach(observablesResource)(_.assertAcquiredOnceAndCleaned)
                    } yield assert(cacheStats)(equalTo(CacheStats(hits = 0, misses = 100, size = 0))) && assert(
                      contains
                    )(forall(isFalse)) &&
                      allCleaned.reduce(_ && _)
                  }
      } yield result
    },
    suite(".get")(
      testM(" when use sequentially, should properly call correct lookup") {
        checkM(Gen.anyInt) { salt =>
          val managedCache =
            ManagedCache.make(100, Duration.Infinity, ManagedLookup { key: Int => Managed.succeed(hash(salt)(key)) })
          managedCache.use { cache =>
            for {
              actual  <- ZIO.foreach(1 to 100)(cache.get(_).use(ZIO.succeed(_)))
              expected = (1 to 100).map(hash(salt))
            } yield assert(actual)(equalTo(expected))
          }
        }
      },
      testM("when use concurrent, should properly call correct lookup") {
        checkM(Gen.anyInt) { salt =>
          val managedCache =
            ManagedCache.make(100, Duration.Infinity, ManagedLookup { key: Int => Managed.succeed(hash(salt)(key)) })
          managedCache.use { cache =>
            for {
              actual  <- ZIO.foreachPar(1 to 100)(cache.get(_).use(ZIO.succeed(_)))
              expected = (1 to 100).map(hash(salt))
            } yield assert(actual)(equalTo(expected))
          }
        }
      },
      testM(".get should clean and remove old resource to respect cache capacity") {
        checkM(Gen.anyInt) { salt =>
          val managedCache =
            ManagedCache.make(10, Duration.Infinity, ManagedLookup { key: Int => Managed.succeed(hash(salt)(key)) })
          managedCache.use { cache =>
            for {
              actual     <- ZIO.foreach(1 to 100)(cache.get(_).use(ZIO.succeed(_)))
              expected    = (1 to 100).map(hash(salt))
              cacheStats <- cache.cacheStats
            } yield assert(actual)(equalTo(expected)) && assert(cacheStats.size)(equalTo(10))
          }
        }
      },
      testM("sequential use on managed returned by a single call to .get should create only one resource") {
        for {
          subResource <- ObservableResourceForTest.makeUnit
          managedCache = ManagedCache.make(
                           capacity = 1,
                           timeToLive = 60.second,
                           lookup = ManagedLookup { _: Unit => subResource.managed }
                         )
          checkInside <-
            managedCache.use { cache =>
              for {
                notAcquiredBeforeAnything                 <- subResource.assertNotAcquired
                resourceManagedProxy                       = cache.get(key = ())
                notAcquireYetAfterGettingManagedFromCache <- subResource.assertNotAcquired
                _                                         <- resourceManagedProxy.use(ZIO.succeed(_))
                acquireOnceAfterUse                       <- subResource.assertAcquiredOnceAndNotCleaned
                _                                         <- resourceManagedProxy.use(ZIO.succeed(_))
                acquireOnceAfterSecondUse                 <- subResource.assertAcquiredOnceAndNotCleaned
              } yield notAcquiredBeforeAnything && notAcquireYetAfterGettingManagedFromCache && acquireOnceAfterUse && acquireOnceAfterSecondUse
            }
          finallyCleaned <- subResource.assertAcquiredOnceAndCleaned
        } yield checkInside && finallyCleaned
      },
      testM("sequentially use of .get should create only one resource") {
        for {
          subResource <- ObservableResourceForTest.makeUnit
          managedCache = ManagedCache.make(
                           capacity = 1,
                           timeToLive = 60.second,
                           lookup = ManagedLookup { _: Unit => subResource.managed }
                         )
          checkInside <- managedCache.use { cache =>
                           for {
                             notAcquiredBeforeAnything <- subResource.assertNotAcquired
                             _                         <- cache.get(key = ()).use(ZIO.succeed(_))
                             acquireOnceAfterUse       <- subResource.assertAcquiredOnceAndNotCleaned
                             _                         <- cache.get(key = ()).use(ZIO.succeed(_))
                             acquireOnceAfterSecondUse <- subResource.assertAcquiredOnceAndNotCleaned
                           } yield notAcquiredBeforeAnything && acquireOnceAfterUse && acquireOnceAfterSecondUse
                         }
          finallyCleaned <- subResource.assertAcquiredOnceAndCleaned
        } yield checkInside && finallyCleaned
      },
      testM("sequential use on failing managed should cache the error and immediately call the resource finalizer") {
        for {
          watchableLookup <-
            WatchableLookup.makeZIO[Unit, Throwable, Nothing](_ => ZIO.fail(new RuntimeException("fail")))
          managedCache = ManagedCache.make(
                           capacity = 1,
                           timeToLive = 60.second,
                           lookup = ManagedLookup(watchableLookup.lookup)
                         )
          testResult <-
            managedCache.use { cache =>
              for {
                notAcquiredBeforeAnything                 <- watchableLookup.assertCalledNum(key = ())(equalTo(0))
                resourceManagedProxy                       = cache.get(key = ())
                notAcquireYetAfterGettingManagedFromCache <- watchableLookup.assertCalledNum(key = ())(equalTo(0))
                _                                         <- resourceManagedProxy.use(ZIO.succeed(_)).either
                acquireAndCleanedRightAway                <- watchableLookup.assertAllCleanedForKey(())
                _                                         <- resourceManagedProxy.use(ZIO.succeed(_)).either
                didNotTryToCreateAnOtherResource          <- watchableLookup.assertCalledNum(key = ())(equalTo(1))
              } yield notAcquiredBeforeAnything && notAcquireYetAfterGettingManagedFromCache && acquireAndCleanedRightAway && didNotTryToCreateAnOtherResource
            }
        } yield testResult
      },
      testM("concurrent use on managed returned by a single call to .get should create only one resource") {
        for {
          subResource <- ObservableResourceForTest.makeUnit
          managedCache = ManagedCache.make(
                           capacity = 1,
                           timeToLive = 60.second,
                           lookup = ManagedLookup { _: Unit => subResource.managed }
                         )
          checkInside <-
            managedCache.use { cache =>
              val managed = cache.get(key = ())
              for {
                Reservation(acquire1, release1)    <- managed.reserve
                Reservation(acquire2, release2)    <- managed.reserve
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
          finallyCleaned <- subResource.assertAcquiredOnceAndCleaned
        } yield checkInside && finallyCleaned
      },
      testM("concurrent use on failing managed should cache the error and immediately call the resource finalizer") {
        for {
          watchableLookup <-
            WatchableLookup.makeZIO[Unit, Throwable, Nothing](_ => ZIO.fail(new RuntimeException("fail")))
          managedCache = ManagedCache.make(
                           capacity = 1,
                           timeToLive = 60.second,
                           lookup = ManagedLookup(watchableLookup.lookup)
                         )
          testResult <-
            managedCache.use { cache =>
              for {
                notAcquiredBeforeAnything                 <- watchableLookup.assertCalledNum(key = ())(equalTo(0))
                resourceManagedProxy                       = cache.get(key = ())
                notAcquireYetAfterGettingManagedFromCache <- watchableLookup.assertCalledNum(key = ())(equalTo(0))
                _                                         <- resourceManagedProxy.use(ZIO.succeed(_)).either <&> resourceManagedProxy.use(ZIO.succeed(_)).either
                acquireAndCleanedRightAway                <- watchableLookup.assertAllCleanedForKey(())
                didNotTryToCreateAnOtherResource          <- watchableLookup.assertCalledNum(key = ())(equalTo(1))
              } yield notAcquiredBeforeAnything && notAcquireYetAfterGettingManagedFromCache && acquireAndCleanedRightAway && didNotTryToCreateAnOtherResource
            }
        } yield testResult
      },
      testM(
        "when two managed returned by two .get call live longer than the cache, the real created subresource should be cleaned only use it's not in use anymore"
      ) {
        for {
          subResource <- ObservableResourceForTest.makeUnit
          managedCache = ManagedCache.make(1, 60.second, ManagedLookup { _: Unit => subResource.managed })
          (release1, release2) <- managedCache.use { cache =>
                                    for {
                                      Reservation(acquire1, release1) <- cache.get(key = ()).reserve
                                      Reservation(acquire2, release2) <- cache.get(key = ()).reserve
                                      _                               <- acquire2
                                      _                               <- acquire1
                                    } yield (release1, release2)
                                  }
          afterSharedManagerLife <- subResource.assertAcquiredOnceAndNotCleaned
          _                      <- release1(Exit.unit)
          afterFirstSubClean     <- subResource.assertAcquiredOnceAndNotCleaned
          _                      <- release2(Exit.unit)
          afterSecondSubClean    <- subResource.assertAcquiredOnceAndCleaned
        } yield afterSharedManagerLife && afterFirstSubClean && afterSecondSubClean
      },
      testM(
        "when two managed obtained by a single managed returned by a single .get call live longer than the cache, the real created subresource should be cleaned only use it's not in use anymore"
      ) {
        for {
          subResource <- ObservableResourceForTest.makeUnit
          managedCache = ManagedCache.make(1, 60.second, ManagedLookup { _: Unit => subResource.managed })
          (release1, release2) <- managedCache.use { cache =>
                                    val manager = cache.get(key = ())
                                    for {
                                      Reservation(acquire1, release1) <- manager.reserve
                                      Reservation(acquire2, release2) <- manager.reserve
                                      _                               <- acquire2
                                      _                               <- acquire1
                                    } yield (release1, release2)
                                  }
          afterSharedManagerLife <- subResource.assertAcquiredOnceAndNotCleaned
          _                      <- release1(Exit.unit)
          afterFirstSubClean     <- subResource.assertAcquiredOnceAndNotCleaned
          _                      <- release2(Exit.unit)
          afterSecondSubClean    <- subResource.assertAcquiredOnceAndCleaned
        } yield afterSharedManagerLife && afterFirstSubClean && afterSecondSubClean
      },
      testM("should clean old resource if cache size is exceeded") {
        val genTestInput = for {
          cacheSize     <- Gen.int(1, 5)
          numCreatedKey <- Gen.int(cacheSize, cacheSize + 3)
        } yield (cacheSize, numCreatedKey)
        checkM(genTestInput) { case (cacheSize, numCreatedKey) =>
          for {
            watchableLookup <- WatchableLookup.make[Int, Unit] { _: Int => () }
            managedCache     = ManagedCache.make(cacheSize, 60.second, ManagedLookup(watchableLookup.lookup))
            testResult <-
              managedCache.use { cache =>
                for {
                  _ <- ZIO.foreach_((0 until numCreatedKey).toList) { key =>
                         cache.get(key).use_(ZIO.unit)
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
          } yield testResult
        }
      }
    ),
    suite("`refresh` method")(
      testM("should update the cache with a new value") {
        def inc(n: Int) = n * 10

        def retrieve(multiplier: Ref[Int])(key: Int) =
          multiplier
            .updateAndGet(inc)
            .map(key * _)

        val seed = 1
        val key  = 123
        for {
          ref <- Ref.make(seed)
          managedCache = ManagedCache.make(
                           1,
                           Duration.Infinity,
                           ManagedLookup { key: Int => Managed.fromEffect(retrieve(ref)(key)) }
                         )
          result <- managedCache.use { cache =>
                      for {
                        val1 <- cache.get(key).use(ZIO.succeed(_))
                        _    <- cache.refresh(key)
                        val2 <- cache.get(key).use(ZIO.succeed(_))
                        val3 <- cache.get(key).use(ZIO.succeed(_))
                      } yield assert(val2)(equalTo[Int, Int](val3) && equalTo[Int, Int](inc(val1)))
                    }
        } yield result
      },
      testM("should clean old resource when making a new one") {
        for {
          watchableLookup <- WatchableLookup.makeUnit
          _               <- ZIO.unit
          managedCache = ManagedCache.make(
                           1,
                           Duration.Infinity,
                           ManagedLookup(watchableLookup.lookup)
                         )
          result <- managedCache.use { cache =>
                      for {
                        _                        <- cache.get(key = ()).use_(ZIO.unit)
                        _                        <- cache.refresh(key = ())
                        createdResources         <- watchableLookup.createdResources.map(_.apply(key = ()))
                        firstResourceCleaned     <- createdResources.head.assertAcquiredOnceAndCleaned
                        secondResourceNotCleaned <- createdResources(1).assertAcquiredOnceAndNotCleaned
                      } yield firstResourceCleaned && secondResourceNotCleaned
                    }
        } yield result
      },
      testM("should update the cache with a new value even if the last `get` or `refresh` failed") {
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
          managedCache = ManagedCache.make(
                           capacity = 1,
                           timeToLive = Duration.Infinity,
                           lookup = ManagedLookup { key: Int => Managed.fromEffect(retrieve(ref)(key)) }
                         )
          result <- managedCache.use { cache =>
                      for {
                        failure1 <- cache.get(key).use(ZIO.succeed(_)).either
                        _        <- cache.refresh(key)
                        val1     <- cache.get(key).use(ZIO.succeed(_)).either
                        _        <- cache.refresh(key)
                        failure2 <- cache.refresh(key).either
                        _        <- cache.refresh(key)
                        val2     <- cache.get(key).use(ZIO.succeed(_)).either
                      } yield assert(failure1)(isLeft(equalTo(error))) &&
                        assert(failure2)(isLeft(equalTo(error))) &&
                        assert(val1)(isRight(equalTo(4))) &&
                        assert(val2)(isRight(equalTo(7)))
                    }
        } yield result
      },
      testM("should create and acquire subresource if the key doesn't exist in the cache") {
        val capacity     = 100
        val managedCache = ManagedCache.make(capacity, Duration.Infinity, ManagedLookup { _: Int => Managed.unit })
        managedCache.use { cache =>
          for {
            count0 <- cache.size
            _      <- ZIO.foreach_(1 to capacity)(cache.refresh(_))
            count1 <- cache.size
          } yield assert(count0)(equalTo(0)) && assert(count1)(equalTo(capacity))
        }
      },
      testM("should clean old resource if cache size is exceeded") {
        val genTestInput = for {
          cacheSize     <- Gen.int(1, 5)
          numCreatedKey <- Gen.int(cacheSize, cacheSize + 3)
        } yield (cacheSize, numCreatedKey)
        checkM(genTestInput) { case (cacheSize, numCreatedKey) =>
          for {
            watchableLookup <- WatchableLookup.make[Int, Unit] { _: Int => () }
            managedCache     = ManagedCache.make(cacheSize, 60.second, ManagedLookup(watchableLookup.lookup))
            testResult <-
              managedCache.use { cache =>
                for {
                  _ <- ZIO.foreach_((0 until numCreatedKey).toList) { key =>
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
          } yield testResult
        }
      }
    ),
    suite("expiration")(
      suite("get")(
        testM(
          "managed returned by .get should recall lookup function if resource is too old and release the previous resource"
        ) {
          for {
            watchableLookup <- WatchableLookup.makeUnit
            fakeClock       <- MockedJavaClock.make
            result <-
              ManagedCache
                .makeWith(capacity = 10, managedLookup = ManagedLookup(watchableLookup.lookup), clock = fakeClock) {
                  _: Exit[Nothing, Unit] =>
                    10.second
                }
                .use { managedCache: ManagedCache[Unit, Nothing, Unit] =>
                  val subManaged = managedCache.get(())
                  for {
                    _                               <- subManaged.use_(ZIO.unit)
                    _                               <- fakeClock.advance(5.second)
                    _                               <- subManaged.use_(ZIO.unit)
                    oneResourceCreatedAfter5second  <- watchableLookup.assertCalledNum(key = ())(equalTo(1))
                    _                               <- fakeClock.advance(4.second)
                    _                               <- subManaged.use_(ZIO.unit)
                    oneResourceCreatedAfter9second  <- watchableLookup.assertCalledNum(key = ())(equalTo(1))
                    _                               <- fakeClock.advance(2.second)
                    _                               <- subManaged.use_(ZIO.unit)
                    twoResourceCreatedAfter11second <- watchableLookup.assertCalledNum(key = ())(equalTo(2))
                    previousResourceCleaned         <- watchableLookup.assertFirstNCreatedResourceCleaned(key = (), 1)
                  } yield oneResourceCreatedAfter5second && oneResourceCreatedAfter9second && twoResourceCreatedAfter11second && previousResourceCleaned
                }
          } yield result
        },
        testM(
          "get should recall lookup function if resource is too old and release old resource (when using multiple time the managed given by .get)"
        ) {
          for {
            watchableLookup <- WatchableLookup.makeUnit
            fakeClock       <- MockedJavaClock.make
            result <- ManagedCache
                        .makeWith(10, ManagedLookup(watchableLookup.lookup), fakeClock) { _: Exit[Nothing, Unit] =>
                          10.second
                        }
                        .use { managedCache: ManagedCache[Unit, Nothing, Unit] =>
                          val useGetManaged = managedCache.get(key = ()).use_(ZIO.unit)
                          for {
                            _                        <- useGetManaged
                            _                        <- fakeClock.advance(5.second)
                            _                        <- useGetManaged
                            after5second             <- watchableLookup.assertCalledNum(key = ())(equalTo(1))
                            _                        <- fakeClock.advance(4.second)
                            _                        <- useGetManaged
                            after9second             <- watchableLookup.assertCalledNum(key = ())(equalTo(1))
                            _                        <- fakeClock.advance(2.second)
                            _                        <- useGetManaged
                            after11second            <- watchableLookup.assertCalledNum(key = ())(equalTo(2))
                            previousResourcesCleaned <- watchableLookup.assertFirstNCreatedResourceCleaned(key = (), 1)
                          } yield after5second && after9second && after11second && previousResourcesCleaned
                        }
          } yield result
        },
        testM(
          "when resource get expired but still used it should wait until resource is not cleaned anymore to clean immediately"
        ) {
          for {
            watchableLookup <- WatchableLookup.makeUnit
            fakeClock       <- MockedJavaClock.make
            result <- ManagedCache
                        .makeWith(10, ManagedLookup(watchableLookup.lookup), fakeClock) { _: Exit[Nothing, Unit] =>
                          10.second
                        }
                        .use { managedCache: ManagedCache[Unit, Nothing, Unit] =>
                          for {
                            Reservation(acquire, release)   <- managedCache.get(()).reserve
                            _                               <- acquire
                            _                               <- fakeClock.advance(11.second)
                            _                               <- managedCache.get(()).use_(ZIO.unit)
                            twoResourcesCreated             <- watchableLookup.assertCalledNum(key = ())(equalTo(2))
                            firstCreatedResource            <- watchableLookup.firstCreatedResource(key = ())
                            notCleanedBeforeItFinishToBeUse <- firstCreatedResource.assertAcquiredOnceAndNotCleaned
                            _                               <- release(Exit.unit)
                            finallyCleanedAfterItsUsed      <- firstCreatedResource.assertAcquiredOnceAndCleaned
                          } yield twoResourcesCreated && notCleanedBeforeItFinishToBeUse && finallyCleanedAfterItsUsed
                        }
          } yield result
        }
      ),
      suite("refresh")(
        testM("should not clean the resource if it's not yet expired until the new resource is ready") {
          for {
            watchableLookup <- WatchableLookup.makeUnit
            fakeClock       <- MockedJavaClock.make
            result <- ManagedCache
                        .makeWith(10, ManagedLookup(watchableLookup.lookup), fakeClock) { _: Exit[Nothing, Unit] =>
                          10.second
                        }
                        .use { managedCache: ManagedCache[Unit, Nothing, Unit] =>
                          for {
                            _            <- managedCache.get(key = ()).use_(ZIO.unit)
                            _            <- fakeClock.advance(9.second)
                            _            <- watchableLookup.lock
                            refreshFiber <- managedCache.refresh(key = ()).fork
                            _ <- watchableLookup
                                   .getCalledNum(key = ())
                                   .repeat(
                                     (Schedule.recurWhile[Int](_ < 1) >>> Schedule.elapsed).whileOutput(_ < 100.millis)
                                   )
                            _                          <- ZIO.sleep(100.millis).provideLayer(Clock.live)
                            secondLookupCalled         <- watchableLookup.assertCalledNum(key = ())(equalTo(2))
                            firstCreatedResource       <- watchableLookup.firstCreatedResource(key = ())
                            firstResourceNotYetCleaned <- firstCreatedResource.assertAcquiredOnceAndNotCleaned
                            _                          <- watchableLookup.unlock
                            _                          <- refreshFiber.join
                            firsResourceFinallyCleaned <- firstCreatedResource.assertAcquiredOnceAndCleaned
                          } yield secondLookupCalled && firstResourceNotYetCleaned && firsResourceFinallyCleaned
                        }
          } yield result
        },
        testM("should clean the resource if it's expired and not in used") {
          for {
            watchableLookup <- WatchableLookup.makeUnit
            fakeClock       <- MockedJavaClock.make
            result <- ManagedCache
                        .makeWith(10, ManagedLookup(watchableLookup.lookup), fakeClock) { _: Exit[Nothing, Unit] =>
                          10.second
                        }
                        .use { managedCache: ManagedCache[Unit, Nothing, Unit] =>
                          for {
                            _            <- managedCache.get(key = ()).use_(ZIO.unit)
                            _            <- fakeClock.advance(11.second)
                            _            <- watchableLookup.lock
                            refreshFiber <- managedCache.refresh(key = ()).fork
                            _ <- watchableLookup
                                   .getCalledNum(key = ())
                                   .repeat(
                                     (Schedule.recurWhile[Int](_ < 1) >>> Schedule.elapsed).whileOutput(_ < 100.millis)
                                   )
                            _                    <- ZIO.sleep(100.millis).provideLayer(Clock.live)
                            secondLookupCalled   <- watchableLookup.assertCalledNum(key = ())(equalTo(2))
                            firstResourceCleaned <- watchableLookup.assertFirstNCreatedResourceCleaned(key = (), 1)
                            _                    <- watchableLookup.unlock
                            _                    <- refreshFiber.join
                          } yield secondLookupCalled && firstResourceCleaned
                        }
          } yield result
        },
        testM("should wait to clean expired resource until it's not in use anymore") {
          for {
            watchableLookup <- WatchableLookup.makeUnit
            fakeClock       <- MockedJavaClock.make
            result <- ManagedCache
                        .makeWith(10, ManagedLookup(watchableLookup.lookup), fakeClock) { _: Exit[Nothing, Unit] =>
                          10.second
                        }
                        .use { managedCache: ManagedCache[Unit, Nothing, Unit] =>
                          for {
                            Reservation(acquire, release) <- managedCache.get(key = ()).reserve
                            _                             <- acquire
                            _                             <- fakeClock.advance(11.second)
                            _                             <- managedCache.refresh(key = ())
                            secondLookupCalled            <- watchableLookup.assertCalledNum(key = ())(equalTo(2))
                            firstCreatedResource          <- watchableLookup.firstCreatedResource(key = ())
                            firstResourceNotYetCleaned    <- firstCreatedResource.assertAcquiredOnceAndNotCleaned
                            _                             <- release(Exit.unit)
                            firsResourceFinallyCleaned    <- firstCreatedResource.assertAcquiredOnceAndCleaned
                          } yield secondLookupCalled && firstResourceNotYetCleaned && firsResourceFinallyCleaned
                        }
          } yield result
        }
      )
    ),
    suite("property base testing")(
      testM(
        "after any suite of balanced resource use, cleaning the cache should release all underlying resources"
      ) {
        import PropertyBaseTestingUtil._
        checkM(balancedSequenceOfAcquireReleaseAndRefresh, Gen.int(1, 20)) { (resourceOperations, cacheSize) =>
          for {
            watchableLookup <- WatchableLookup.make[Key, Unit] { _: Key => () }
            releasers <-
              ManagedCache
                .makeWith(cacheSize, managedLookup = ManagedLookup(watchableLookup.lookup)) { _: Exit[Nothing, Unit] =>
                  10.second
                }
                .use(applyResourceOperations(_, resourceOperations))
            allCleaned <- watchableLookup.assertAllCleaned
          } yield allCleaned && assert(releasers)(isEmpty)
        }
      },
      testM(
        "after any suite of resource use, cleaning the cache should only resource not in used, others should be cleaned after there are not used anymore"
      ) {
        import PropertyBaseTestingUtil._
        checkM(sequenceOfAcquireReleaseAndRefreshLettingSomeResourceUsed, Gen.int(1, 20)) {
          case (ResourceOperationsAndResult(resourceOperations, resourceCleaned, resourceNotCleaned), cacheSize) =>
            for {
              watchableLookup <- WatchableLookup.make[Key, Unit] { _: Key => () }
              notUsedReleasers <-
                ManagedCache
                  .make(capacity = cacheSize, timeToLive = 10.second, lookup = ManagedLookup(watchableLookup.lookup))
                  .use(applyResourceOperations(_, resourceOperations))
              allResourceNotInUseAnymoreCleaned <-
                assertAllM(resourceCleaned.map(watchableLookup.assertAllCleanedForKey))

              allResourceInUseAnymoreNotCleaned <-
                assertAllM(resourceNotCleaned.map(watchableLookup.assertAtLeastOneResourceNotCleanedForKey))
              _                                            <- ZIO.foreach(notUsedReleasers.values)(_.apply(Exit.unit))
              allCleanedAfterAllResourceAreNotInUseAnymore <- watchableLookup.assertAllCleaned
            } yield allResourceNotInUseAnymoreCleaned && allResourceInUseAnymoreNotCleaned && allCleanedAfterAllResourceAreNotInUseAnymore
        }
      },
      testM(
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

        checkM(genTestInput) { case TestInput(resourceOperations, cacheSize, sequenceOfFailureOrSuccess) =>
          for {
            lookupCallNum <- Ref.make(0)
            watchableLookup <- WatchableLookup.makeZIO[Key, Throwable, Unit] { _: Key =>
                                 lookupCallNum.getAndUpdate(_ + 1).flatMap { index =>
                                   ZIO.fail(new RuntimeException("fail")).unless(sequenceOfFailureOrSuccess(index))
                                 }
                               }
            releasers <-
              ManagedCache
                .make(capacity = cacheSize, timeToLive = 10.second, lookup = ManagedLookup(watchableLookup.lookup))
                .use(applyResourceOperations(_, resourceOperations, ignoreCacheError = true))
            allCleaned <- watchableLookup.assertAllCleaned
          } yield allCleaned && assert(releasers)(isEmpty)
        }
      }
    )
  )

  type Releaser = Exit[Any, Any] => UIO[Any]

  sealed trait ObservableResourceForTest[E, V] {
    def assertNotAcquired: UIO[TestResult]

    def assertAcquiredOnceAndNotCleaned: UIO[TestResult]

    def assertAcquiredOnceAndCleaned: UIO[TestResult]

    def managed: Managed[E, V]
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

      override def managed: Managed[E, V] = ZManaged.makeReserve(
        ZIO.succeed(
          Reservation(
            acquire = resourceAcquisitionCount.update(_ + 1) *> effect,
            release = { _ => resourceAcquisitionReleasing.update(_ + 1) }
          )
        )
      )
    }
  }

  sealed trait WatchableLookup[K, E, V] {
    def lookup(key: K): Managed[E, V]

    def createdResources: UIO[Map[K, List[ObservableResourceForTest[E, V]]]]

    def assertCalledNum(key: K)(sizeAssertion: Assertion[Int]): UIO[TestResult] =
      assertM(getCalledNum(key))(sizeAssertion)

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
      override def lookup(key: K): Managed[E, V] = Managed.unwrap(for {
        observableResource <- ObservableResourceForTest.makeZIO(concreteLookup(key))
        _ <- resources.update(_.updatedWith(key) { previous =>
               Some(
                 previous.getOrElse(List.empty).appended(observableResource)
               )
             })
        _ <- blocked.get
               .repeat(Schedule.recurWhile[Boolean](identity) && Schedule.exponential(10.millis, 2.0))
               .provideLayer(Clock.live)
      } yield observableResource.managed)

      override val createdResources: UIO[Map[K, List[ObservableResourceForTest[E, V]]]] = resources.get

      override val lock   = blocked.set(true)
      override val unlock = blocked.set(false)
    }

    def make[K, V](concreteLookup: K => V): UIO[WatchableLookup[K, Nothing, V]] = makeZIO(
      concreteLookup.andThen(ZIO.succeed(_))
    )

    def makeUnit: UIO[WatchableLookup[Unit, Nothing, Unit]] = make { _: Unit => () }
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
    ): Gen[Random, ResourceOperationsAndResult] = if (
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
        Gen.elements[ResourceOperation]((notOpenYet.toList.map(Acquire) ++ openedButNotCleaned.toList.map(Release)): _*)
      val refresh = Gen.elements[ResourceOperation](allKey.map(Refresh).toList: _*)
      val invalidatePresent = Gen.elements[ResourceOperation](openedButNotCleaned.map { totalId =>
        Invalidate(totalId.key)
      }.toList: _*)
      val invalidateNotPresent =
        Gen.elements[ResourceOperation]((allKey -- openedButNotCleaned.map(_.key)).map(Invalidate).toList: _*)
      for {
        nextOp <-
          Gen.weighted((acquireOrRelease, 8.0), (refresh, 2.0), (invalidatePresent, 2.0), (invalidateNotPresent, 1.0))
        (newOpened, newNotOpenYet) = nextOp match {
                                       case Acquire(id)     => (notOpenYet - id, openedButNotCleaned + id)
                                       case Release(id)     => (notOpenYet, openedButNotCleaned - id)
                                       case Refresh(_)      => (notOpenYet, openedButNotCleaned)
                                       case Invalidate(key) => (notOpenYet, openedButNotCleaned)
                                     }
        result <-
          sequenceOfAcquireReleaseAndRefreshRec(
            previousResourceOperation = previousResourceOperation.appended(nextOp),
            allKey = allKey,
            notOpenYet = newOpened,
            openedButNotCleaned = newNotOpenYet,
            acceptResourceNotCleaned = acceptResourceNotCleaned
          )
      } yield result
    }

    val balancedSequenceOfAcquireReleaseAndRefresh: Gen[Random with Sized, List[ResourceOperation]] = {
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

    val sequenceOfAcquireReleaseAndRefreshLettingSomeResourceUsed
      : Gen[Random with Sized, ResourceOperationsAndResult] = {
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
      managedCache: ManagedCache[Key, Throwable, V],
      resourceOperations: List[ResourceOperation],
      ignoreCacheError: Boolean = false
    ): IO[TestFailure[Nothing], Map[ResourceId, Releaser]] =
      for {
        notUsedReleasers <-
          ZIO.foldLeft(resourceOperations)(Map.empty[ResourceId, Releaser]) { (releasers, resourceOperation) =>
            resourceOperation match {
              case Acquire(totalId @ ResourceId(key, _)) =>
                managedCache
                  .get(key)
                  .reserve
                  .flatMap { case Reservation(acquire, release) =>
                    acquire.as(releasers.updated(totalId, release))
                  }
                  .catchAll { error =>
                    if (ignoreCacheError) {
                      ZIO.succeed(releasers)
                    } else {
                      ZIO.fail(TestFailure.die(error))
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
                managedCache
                  .refresh(key)
                  .catchAll { error =>
                    ZIO.fail(TestFailure.die(error)).unless(ignoreCacheError)
                  }
                  .as(releasers)
              case Invalidate(key) =>
                managedCache
                  .invalidate(key)
                  .as(releasers)
            }
          }
      } yield notUsedReleasers
  }

  def assertAllM(results: Iterable[UIO[TestResult]]): UIO[TestResult] =
    ZIO.foldLeft(results)(assertCompletes)((l, r) => r.map(l && _))

  def assertOneOfM(results: Iterable[UIO[TestResult]]): UIO[TestResult] =
    ZIO.foldLeft(results)(assertCompletes.negate)((l, r) => r.map(l || _))
}
