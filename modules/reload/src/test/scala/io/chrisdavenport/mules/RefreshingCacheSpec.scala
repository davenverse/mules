package io.chrisdavenport.mules
package reload
import cats.effect.IO
import cats.effect.concurrent.Ref
import cats.implicits._
import io.chrisdavenport.mules.reload.RefreshingCache.RefreshDurationTooLong
import org.specs2.mutable.Specification

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class RefreshingCacheSpec extends Specification {

  implicit val ctx = IO.contextShift(ExecutionContext.global)
  implicit val timer = IO.timer(ExecutionContext.Implicits.global)

  val countF = Ref.of[IO, Int](0)
  def expiresIn(duration: FiniteDuration) = Some(TimeSpec.unsafeFromDuration(duration))
  def autoFetchEvery(duration: FiniteDuration) = TimeSpec.unsafeFromDuration(duration)

  "RefreshingCache" should {

    "returns None if the key isn't present" in {
      val setup = for {
         cache <- RefreshingCache.createCache[IO, String, Int](None)
         value <- cache.lookup("Foo")

      } yield value
      setup.unsafeRunSync must_=== None
    }

    "returns Some(value) if the value is inserted" in {
      val setup = for {
         cache <- RefreshingCache.createCache[IO, String, Int](expiresIn(1.second))
         _ <- cache.insert("Foo", 1)
         value <- cache.lookup("Foo")

      } yield value
      setup.unsafeRunSync must_=== Some(1)
    }

    "returns None if the value is inserted but expired" in {
      val setup = for {
         cache <- RefreshingCache.createCache[IO, String, Int](expiresIn(500.milliseconds))
         _ <- cache.insert("Foo", 1)
         _ <- timer.sleep(1.second)
         value <- cache.lookup("Foo")

      } yield value

      setup.unsafeRunSync must_=== None
    }

    "fetch value if a fetch is given" in {
      val setup = for {
        count <- Ref.of[IO, Int](0)
        cache <- RefreshingCache.createCache[IO, String, Int](None)
        value <- cache.lookupOrFetch("Foo")(count.get)
      } yield value

      setup.unsafeRunSync must_=== 0
    }

    "fetch value if an auto fetch is given" in {
      val setup = for {
        count <- Ref.of[IO, Int](0)
        cache <- RefreshingCache.createCache[IO, String, Int](None)
        value <- cache.lookupOrRefresh("Foo", autoFetchEvery(500.milliseconds))(count.get)
        _ <- cache.cancelRefreshes
      } yield value

      setup.unsafeRunSync must_=== 0
    }

    "return Cached value before auto refresh" in {
      val setup = for {
        count <- Ref.of[IO, Int](0)
        cache <- RefreshingCache.createCache[IO, String, Int](None)
        _ <- cache.lookupOrRefresh("Foo", autoFetchEvery(1.second))(count.get)
        _ <- count.update(_ + 1)
        _ <- timer.sleep(500.milliseconds)
        value <- cache.lookup("Foo")
        _ <- cache.cancelRefreshes
      } yield value

      setup.unsafeRunSync must_=== Some(0)
    }

    "return new value after auto refresh" in {
      val setup = for {
        count <- Ref.of[IO, Int](0)
        cache <- RefreshingCache.createCache[IO, String, Int](None)
        _ <- cache.lookupOrRefresh("Foo", autoFetchEvery(250.milliseconds))(count.get)
        _ <- count.update(_ + 1)
        _ <- timer.sleep(300.milliseconds)
        firstRead <- cache.lookup("Foo")
        _ <- count.update(_ + 1)
        _ <- timer.sleep(300.milliseconds)
        secondRead <- cache.lookup("Foo")
        _ <- cache.cancelRefreshes
      } yield (firstRead, secondRead)

      val (firstRead, secondRead) = setup.unsafeRunSync
      firstRead must_=== Some(1)
      secondRead must_=== Some(2)
    }

    "keeps from expiration through by auto refresh" in {
      val setup = for {
        cache <- RefreshingCache.createCache[IO, String, Int](expiresIn(1.second) )
        _ <- cache.lookupOrRefresh("Foo", autoFetchEvery(500.milliseconds))(IO.pure(1))
        _ <- timer.sleep(1500.milliseconds)
        value <- cache.lookup("Foo")
        _ <- cache.cancelRefreshes
      } yield value

      setup.unsafeRunSync must_=== Some(1)
    }

    "fails to setup auto fetch if refresh is longer than default expiration" in {
      val setup = for {
        cache <- RefreshingCache.createCache[IO, String, Int](expiresIn(1.second) )
        r <- cache.lookupOrRefresh("Foo", autoFetchEvery(1001.milliseconds))(IO.pure(1))
        _ <- cache.cancelRefreshes
      } yield r

      setup.attempt.unsafeRunSync().leftMap(_.getMessage) must_=== Left(
        RefreshDurationTooLong(
          duration = TimeSpec.unsafeFromDuration(1001.milliseconds),
          expiration = TimeSpec.unsafeFromDuration(1.second)
        ).getMessage)
    }


    "auto refresh does not reinsert after deletion" in {
      val setup = for {
        cache <- RefreshingCache.createCache[IO, String, Int](None)
        _ <- cache.lookupOrRefresh("Foo", autoFetchEvery(500.milliseconds))(IO.pure(1))
        _ <- cache.delete("Foo")
        _ <- timer.sleep(750.milliseconds)
        value <- cache.lookup("Foo")
        _ <- cache.cancelRefreshes
      } yield value

      setup.unsafeRunSync must_=== None

    }

    "auto refresh turns off after deletion" in {
      val setup = for {
        count <- Ref.of[IO, Int](0)
        cache <- RefreshingCache.createCache[IO, String, Int](None)
        _ <- cache.lookupOrRefresh("Foo", autoFetchEvery(500.milliseconds))(count.update(_ + 1).as(1))
        _ <- cache.delete("Foo")
        _ <- timer.sleep(750.milliseconds)
        cValue <- count.get
      } yield cValue

      setup.unsafeRunSync must_=== 1
    }

    "does not register auto refresh if the key already exist" in {
      val setup = for {
        count <- Ref.of[IO, Int](0)
        cache <- RefreshingCache.createCache[IO, String, Int](None)
        _ <- cache.insert("Foo", 1)
        _ <- cache.lookupOrRefresh("Foo",  autoFetchEvery(500.milliseconds))(count.update(_ + 1).as(1))
        _ <- timer.sleep(750.milliseconds)
        cValue <- count.get
      } yield cValue

      setup.unsafeRunSync must_=== 0
    }

    "failed refresh fetch can be recovered" in {
      val setup = for {
        count <- Ref.of[IO, Int](0)
        cache <- RefreshingCache.createCache[IO, String, Int](None)

        _ <- cache.lookupOrRefresh("Foo", autoFetchEvery(250.milliseconds), None)
            { count.get.ensure(IntentionalExceptionForTesting)(_ != 1) }
            { case IntentionalExceptionForTesting => IO.unit}
        _ <- count.update(_ + 1)
        _ <- timer.sleep(300.milliseconds)
        firstRead <- cache.lookup("Foo")
        _ <- count.update(_ + 1)
        _ <- timer.sleep(300.milliseconds)
        secondRead <- cache.lookup("Foo")
        _ <- cache.cancelRefreshes
      } yield (firstRead, secondRead)

      val (firstRead, secondRead) = setup.unsafeRunSync
      firstRead must_=== Some(0) //return the old value
      secondRead must_== Some(2)
    }



    "failed refresh fetch stops refresh if recover doesn't catch" in {
      val setup = for {
        count <- Ref.of[IO, Int](0)

        cache <- RefreshingCache.createCache[IO, String, Int](None)

        _ <- cache.lookupOrRefresh("Foo", autoFetchEvery(100.milliseconds), None )
              { count.update(_ + 1) *> count.get.ensure(new Exception("unknown error"))(_ <= 1) }
              { case IntentionalExceptionForTesting => IO.unit}

        _ <- timer.sleep(250.milliseconds)
        value <- cache.lookup("Foo")
        refreshesLeft <- cache.cancelRefreshes
        readCount <- count.get

      } yield (value, readCount, refreshesLeft)

      val (value, readCount, refreshes) = setup.unsafeRunSync
      value must_=== Some(1)
      readCount must_==(2)
      refreshes must_=== 0
    }

    "value and refresh eventually expires on continuous fetch failures" in {
      val setup = for {
        count <- Ref.of[IO, Int](0)

        cache <- RefreshingCache.createCache[IO, String, Int](None)

        _ <- cache.lookupOrRefresh("Foo", autoFetchEvery(100.milliseconds), expiresIn(300.milliseconds))
              { count.update(_ + 1) *> count.get.ensure(IntentionalExceptionForTesting)(_ <= 1) }
              { case IntentionalExceptionForTesting => IO.unit}

        _ <- timer.sleep(700.milliseconds)
        value <- cache.lookup("Foo")
        readCount <- count.get
        refreshesLeft <- cache.cancelRefreshes
      } yield (value, readCount, refreshesLeft)

      val (value, readCount, refreshes) = setup.unsafeRunSync
      value must_=== None
      readCount must be_<(4)
      refreshes must_=== 0
    }

    "auto refresh use default expiration if not given one " in {
      val setup = for {
        count <- Ref.of[IO, Int](0)

        cache <- RefreshingCache.createCache[IO, String, Int](expiresIn(200.milliseconds))

        _ <- cache.lookupOrRefresh("Foo", autoFetchEvery(100.milliseconds))
                                  { count.update(_ + 1) *> count.get.ensure(IntentionalExceptionForTesting)(_ <= 1) }


        _ <- timer.sleep(250.milliseconds)
        value <- cache.lookup("Foo")
      } yield value

      setup.unsafeRunSync must beNone

    }

  }
}

case object IntentionalExceptionForTesting extends RuntimeException