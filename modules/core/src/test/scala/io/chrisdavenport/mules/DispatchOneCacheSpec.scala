package io.chrisdavenport.mules

import org.specs2.mutable.Specification
import scala.concurrent.duration._
import cats.implicits._
import cats.effect._
import cats.effect.concurrent._
// import cats.effect.implicits._
import cats.effect.IO
import cats.effect.testing.specs2.CatsIO
import cats.effect.{ Ref, Temporal }

class DispatchOneCacheSpec extends Specification with CatsIO {
  "DispatchOneCache" should {
    "only run once" in {
      for {
        ref <- Ref[IO].of(0)
        cache <- DispatchOneCache.ofSingleImmutableMap[IO, Unit, Int](None)
        action = {_: Unit => Temporal[IO].sleep(1.second) >> ref.modify(i => (i+1, i))}
        first <- cache.lookupOrLoad((), action).start
        second <- cache.lookupOrLoad((), action).start
        third <- cache.lookupOrLoad((), action).start
        _ <- first.join
        _ <- second.join
        _ <- third.join
        testValue <- ref.get
      } yield testValue must_=== 1
    }

    "only run till errors cease" in {
      for {
        ref <- Ref[IO].of(0)
        errorFunction = ref.modify(i => (i+1, if (i > 3) i.pure[IO] else  Temporal[IO].sleep(1.second) >> IO.raiseError(new Throwable("whoopsie")))).flatten
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

    "insert places a value" in {
      for {
        cache <- DispatchOneCache.ofSingleImmutableMap[IO, Unit, Int](None)
        action = {_: Unit => IO.pure(5)}
        _ <- cache.insert((), 1)
        now <- cache.lookupOrLoad((), action)
      } yield {
        now must_=== 1
      }
    }

    "insert overrides background action for first action" in {
      for {
        cache <- DispatchOneCache.ofSingleImmutableMap[IO, Unit, Int](None)
        action = {_: Unit => Temporal[IO].sleep(5.seconds).as(5)}
        first <- cache.lookupOrLoad((), action).start
        _ <- cache.insert((), 1)
        value <- first.join
      } yield {
        value must_=== 1
      }
    }

    "insert overrides background action for secondary action" in {
      for {
        cache <- DispatchOneCache.ofSingleImmutableMap[IO, Unit, Int](None)
        action = {_: Unit => Temporal[IO].sleep(5.seconds).as(5)}
        first <- cache.lookupOrLoad((),action).start
        second <- cache.lookupOrLoad((), action).start
        _ <- cache.insert((), 1)
        resultSecond <- second.join
        _ <- first.cancel.timeout(1.second).attempt.void
      } yield {
        resultSecond must_=== 1
      }
    }


    "insert overrides set value" in {
      for {
        cache <- DispatchOneCache.ofSingleImmutableMap[IO, Unit, Int](None)
        action = {_: Unit => IO.pure(2)}
        first <- cache.lookupOrLoad((), action)
        _ <- cache.insert((), 1)
        second <- cache.lookupOrLoad((), action)
      } yield {
        (first,second).must_===((2, 1))
        
      }
    }
  }
}