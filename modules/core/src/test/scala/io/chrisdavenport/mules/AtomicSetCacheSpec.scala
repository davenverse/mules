package io.chrisdavenport.mules

import org.specs2.mutable.Specification
import scala.concurrent.duration._
import cats.implicits._
import cats.effect._
import cats.effect.concurrent._
// import cats.effect.implicits._
import cats.effect.IO
import cats.effect.specs2.CatsIO

class AtomicSetCacheSpec extends Specification with CatsIO {
  "AtomicSetCache" should {
    "only run once" in {
      for {
        ref <- Ref[IO].of(0)
        cache <- AtomicSetCache.ofSingleImmutableMap[IO, Unit, Int](_ => Timer[IO].sleep(1.second) >> ref.modify(i => (i+1, i)), None)
        first <- cache.get(()).start
        second <- cache.get(()).start
        third <- cache.get(()).start
        _ <- first.join
        _ <- second.join
        _ <- third.join
        testValue <- ref.get
      } yield testValue must_=== 1
    }

    "only run till errors cease" in {
      for {
        ref <- Ref[IO].of(0)
        errorFunction = ref.modify(i => (i+1, if (i > 3) i.pure[IO] else  Timer[IO].sleep(1.second) >> IO.raiseError(new Throwable("whoopsie")))).flatten
        cache <- AtomicSetCache.ofSingleImmutableMap[IO, Unit, Int](_ => errorFunction, None)
        first <- cache.get(()).start
        second <- cache.get(()).start
        third <- cache.get(()).start
        _ <- first.join
        _ <- second.join
        _ <- third.join
        testValue <- ref.get
      } yield testValue must_=== 5
    }

    "insert places a value" in {
      for {
        cache <- AtomicSetCache.ofSingleImmutableMap[IO, Unit, Int](_ => IO.pure(5), None)
        _ <- cache.insert((), 1)
        now <- cache.get(())
      } yield {
        now must_=== 1
      }
    }

    "insert overrides background action for first action get" in {
      for {
        cache <- AtomicSetCache.ofSingleImmutableMap[IO, Unit, Int](_ => Timer[IO].sleep(5.seconds).as(5), None)
        first <- cache.get(()).start
        _ <- cache.insert((), 1)
        value <- first.join
      } yield {
        value must_=== 1
      }
    }

    "insert overrides background action for secondary action get" in {
      for {
        cache <- AtomicSetCache.ofSingleImmutableMap[IO, Unit, Int](_ => Timer[IO].sleep(5.seconds).as(5), None)
        first <- cache.get(()).start
        second <- cache.get(()).start
        _ <- cache.insert((), 1)
        resultSecond <- second.join
        _ <- first.cancel.timeout(1.second).attempt.void
      } yield {
        resultSecond must_=== 1
      }
    }


    "insert overrides set value" in {
      for {
        cache <- AtomicSetCache.ofSingleImmutableMap[IO, Unit, Int](_ => IO.pure(2), None)
        first <- cache.get(())
        _ <- cache.insert((), 1)
        second <- cache.get(())
      } yield {
        (first,second).must_===((2, 1))
        
      }
    }
  }
}