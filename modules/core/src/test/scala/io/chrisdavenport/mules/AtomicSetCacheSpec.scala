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
        cache <- AtomicSetCache.ofConcurrentHashMap[IO, Unit, Int](_ => Timer[IO].sleep(1.second) >> ref.modify(i => (i+1, i)), None)
        first <- cache.lookupOrGet(()).start
        second <- cache.lookupOrGet(()).start
        third <- cache.lookupOrGet(()).start
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
        cache <- AtomicSetCache.ofConcurrentHashMap[IO, Unit, Int](_ => errorFunction, None)
        first <- cache.lookupOrGet(()).start
        second <- cache.lookupOrGet(()).start
        third <- cache.lookupOrGet(()).start
        _ <- first.join
        _ <- second.join
        _ <- third.join
        testValue <- ref.get
      } yield testValue must_=== 5
    }

  }
}