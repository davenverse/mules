package io.chrisdavenport.mules

import scala.concurrent.duration._
import cats.syntax.all._
import cats.effect._
import munit._

class DispatchOneCacheSpec extends CatsEffectSuite {
  test("DispatchOneCache should only run once") {
    for {
      ref <- Ref[IO].of(0)
      cache <- DispatchOneCache.ofSingleImmutableMap[IO, Unit, Int](None)
      action = {(_: Unit) => Temporal[IO].sleep(1.second) >> ref.modify(i => (i+1, i))}
      first <- cache.lookupOrLoad((), action).start
      second <- cache.lookupOrLoad((), action).start
      third <- cache.lookupOrLoad((), action).start
      _ <- first.join
      _ <- second.join
      _ <- third.join
      testValue <- ref.get
    } yield assertEquals(testValue, 1)
  }

    /*
    "only run till errors cease" in {
      for {
        ref <- Ref[IO].of(0)
        errorFunction = ref.modify(i => (i+1, if (i > 3) i.pure[IO] else  Timer[IO].sleep(1.second) >> IO.raiseError(new Throwable("whoopsie")))).flatten
        cache <- DispatchOneCache.ofSingleImmutableMap[IO, Unit, Int](None)
        first <- cache.lookupOrLoad((), { _ => errorFunction}).start
        second <- cache.lookupOrLoad((), { _ => errorFunction}).start
        third <- cache.lookupOrLoad((), { _ => errorFunction}).start
        _ <- first.join
        _ <- second.join
        _ <- third.join
        testValue <- ref.get
      } yield testValue must_=== 5
    }
    */

  test("DispatchOneCache should insert places a value") {
    for {
      cache <- DispatchOneCache.ofSingleImmutableMap[IO, Unit, Int](None)
      action = {(_: Unit) => IO.pure(5)}
      _ <- cache.insert((), 1)
      now <- cache.lookupOrLoad((), action)
    } yield {
      assertEquals(now, 1)
    }
  }

  /*
  test("DispatchOneCache should insert overrides background action for first action") {
    for {
      cache <- DispatchOneCache.ofSingleImmutableMap[IO, Unit, Int](None)
      action = {(_: Unit) => Temporal[IO].sleep(5.seconds).as(5)}
      first <- cache.lookupOrLoad((), action).start
      _ <- cache.insert((), 1)
      value <- first.join
    } yield {
      assertEquals(value, Outcome.Succeeded[IO,Throwable,Int](1.pure[IO]))
    }
  }
  */


    /*
    "insert overrides background action for secondary action" in {
      for {
        cache <- DispatchOneCache.ofSingleImmutableMap[IO, Unit, Int](None)
        action = {_: Unit => Timer[IO].sleep(5.seconds).as(5)}
        first <- cache.lookupOrLoad((),action).start
        second <- cache.lookupOrLoad((), action).start
        _ <- cache.insert((), 1)
        resultSecond <- second.join
        _ <- first.cancel.timeout(1.second).attempt.void
      } yield {
        resultSecond must_=== 1
      }
    }
    */

  test("DispatchOneCache should insert overrides set value") {
    for {
      cache <- DispatchOneCache.ofSingleImmutableMap[IO, Unit, Int](None)
      action = {(_: Unit) => IO.pure(2)}
      first <- cache.lookupOrLoad((), action)
      _ <- cache.insert((), 1)
      second <- cache.lookupOrLoad((), action)
    } yield {
      assertEquals(first, 2)
      assertEquals(second, 1)
    }
  }
  // No way to directly test the actual cache constructors, so we copy the construction and test that.
  test("DispatchOneCache.ofConcurrentHashMap should expire keys") {
    Resource.eval(PurgeableMapRef.ofConcurrentHashMap[IO, Unit, DispatchOneCache.DispatchOneCacheItem[IO, Int]](isExpired = DispatchOneCache.isExpired[IO,Int]))
      .fproduct(pmr =>DispatchOneCache.ofMapRef[IO,Unit, Int](pmr.mapRef,TimeSpec.fromDuration(500.millis), pmr.purgeExpiredEntries.some))
      .flatMap{ case (pmr,cache) => DispatchOneCache.liftToAuto[IO, Unit, Int](
        cache,
        TimeSpec.unsafeFromDuration(200.millis)
      ).as((pmr, cache))}
      .use {case (pmr, cache) => for {
        _ <- cache.insert((), 1)
        _ <- IO.sleep(300.millis)
        first <- cache.lookup(())
        _ <- IO.sleep(500.millis)
        cacheItem <- pmr.mapRef(()).get
        second <- cacheItem.traverse(_.item.get)
      } yield {
        assert(first.contains(1))
        assertEquals(second, None)
      }}
  }

  test("DispatchOneCache.ofShardedImmutableMap should expire keys") {
    Resource.eval(PurgeableMapRef.ofShardedImmutableMap[IO, Unit, DispatchOneCache.DispatchOneCacheItem[IO, Int]](4, DispatchOneCache.isExpired[IO,Int]))
      .fproduct(pmr =>DispatchOneCache.ofMapRef[IO,Unit, Int](pmr.mapRef,TimeSpec.fromDuration(500.millis), pmr.purgeExpiredEntries.some))
      .flatMap{ case (pmr,cache) => DispatchOneCache.liftToAuto[IO, Unit, Int](
        cache,
        TimeSpec.unsafeFromDuration(200.millis)
      ).as((pmr, cache))}
      .use {case (pmr, cache) => for {
        _ <- cache.insert((), 1)
        _ <- IO.sleep(300.millis)
        first <- cache.lookup(())
        _ <- IO.sleep(500.millis)
        cacheItem <- pmr.mapRef(()).get
        second <- cacheItem.traverse(_.item.get)
      } yield {
        assert(first.contains(1))
        assertEquals(second, None)
      }}
  }
}
