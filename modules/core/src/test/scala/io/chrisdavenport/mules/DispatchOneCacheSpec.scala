package io.chrisdavenport.mules

import scala.concurrent.duration._
import cats.syntax.all._
import cats.effect._
import cats.effect.concurrent._
import munit._

class DispatchOneCacheSpec extends CatsEffectSuite {
  test("DispatchOneCache should only run once") {
    for {
      ref <- Ref[IO].of(0)
      cache <- DispatchOneCache.ofSingleImmutableMap[IO, Unit, Int](None)
      action = {_: Unit => Timer[IO].sleep(1.second) >> ref.modify(i => (i+1, i))}
      first <- cache.lookupOrLoad((), action).start
      second <- cache.lookupOrLoad((), action).start
      third <- cache.lookupOrLoad((), action).start
      _ <- first.join
      _ <- second.join
      _ <- third.join
      testValue <- ref.get
    } yield assertEquals(testValue, 1)
  }

  test("DispatchOneCache should only run till errors cease") {
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
    } yield assertEquals(testValue, 5)
  }

  test("DispatchOneCache should insert places a value") {
    for {
      cache <- DispatchOneCache.ofSingleImmutableMap[IO, Unit, Int](None)
      action = {_: Unit => IO.pure(5)}
      _ <- cache.insert((), 1)
      now <- cache.lookupOrLoad((), action)
    } yield {
      assertEquals(now, 1)
    }
  }

  test("DispatchOneCache should insert overrides background action for first action") {
    for {
      cache <- DispatchOneCache.ofSingleImmutableMap[IO, Unit, Int](None)
      action = {_: Unit => Timer[IO].sleep(5.seconds).as(5)}
      first <- cache.lookupOrLoad((), action).start
      _ <- cache.insert((), 1)
      value <- first.join
    } yield {
      assertEquals(value, 1)
    }
  }

  test("DispatchOneCache should insert overrides background action for secondary action") {
    for {
      cache <- DispatchOneCache.ofSingleImmutableMap[IO, Unit, Int](None)
      action = {_: Unit => Timer[IO].sleep(5.seconds).as(5)}
      first <- cache.lookupOrLoad((),action).start
      second <- cache.lookupOrLoad((), action).start
      _ <- cache.insert((), 1)
      resultSecond <- second.join
      _ <- first.cancel.timeout(1.second).attempt.void
    } yield {
      assertEquals(resultSecond, 1)
    }
  }


  test("DispatchOneCache should insert overrides set value") {
    for {
      cache <- DispatchOneCache.ofSingleImmutableMap[IO, Unit, Int](None)
      action = {_: Unit => IO.pure(2)}
      first <- cache.lookupOrLoad((), action)
      _ <- cache.insert((), 1)
      second <- cache.lookupOrLoad((), action)
    } yield {
      assertEquals(first, 2)
      assertEquals(second, 1)
    }
  }
}
