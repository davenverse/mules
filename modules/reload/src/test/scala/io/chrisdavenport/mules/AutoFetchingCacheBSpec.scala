package io.chrisdavenport.mules
package reload
import cats.effect.IO
import cats.effect.concurrent.Ref
import cats.implicits._
import org.specs2.mutable.Specification

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class AutoFetchingCacheBSpec extends Specification {

  implicit val ctx = IO.contextShift(ExecutionContext.global)
  implicit val timer = IO.timer(ExecutionContext.Implicits.global)

  val countF = Ref.of[IO, Int](0)
  def expiresIn(duration: FiniteDuration) = Some(TimeSpec.unsafeFromDuration(duration))
  def autoFetchEvery(duration: FiniteDuration) = TimeSpec.unsafeFromDuration(duration)

  "AutoFetchingCacheB" should {

    "returns None if the key isn't present" in {
      val setup = for {
         cache <- AutoFetchingCacheB.createCache[IO, String, Int](expiresIn(1.second))
         value <- cache.lookup("Foo")

      } yield value
      setup.unsafeRunSync must_=== None
    }

    "returns Some(value) if the value is inserted" in {
      val setup = for {
         cache <- AutoFetchingCacheB.createCache[IO, String, Int](expiresIn(1.second))
         _ <- cache.insert("Foo", 1)
         value <- cache.lookup("Foo")

      } yield value
      setup.unsafeRunSync must_=== Some(1)
    }


    "returns None if the value is inserted but expired" in {
      val setup = for {
         cache <- AutoFetchingCacheB.createCache[IO, String, Int](expiresIn(500.milliseconds))
         _ <- cache.insert("Foo", 1)
         _ <- timer.sleep(1.second)
         value <- cache.lookup("Foo")

      } yield value

      setup.unsafeRunSync must_=== None
    }



    "fetch value if a fetch is given" in {
      val setup = for {
        count <- Ref.of[IO, Int](0)
        cache <- AutoFetchingCacheB.createCache[IO, String, Int](expiresIn(1.second))
        value <- cache.lookupOrFetch("Foo", count.get)
      } yield value

      setup.unsafeRunSync must_=== 0
    }

    "fetch value if an auto fetch is given" in {
      val setup = for {
        count <- Ref.of[IO, Int](0)
        cache <- AutoFetchingCacheB.createCache[IO, String, Int](expiresIn(1.second))
        value <- cache.lookupOrAutoFetch("Foo", count.get, autoFetchEvery(500.milliseconds))
      } yield value

      setup.unsafeRunSync must_=== 0
    }

    "return Cached value before auto refresh" in {
      val setup = for {
        count <- Ref.of[IO, Int](0)
        cache <- AutoFetchingCacheB.createCache[IO, String, Int](
          expiresIn(2.seconds))
        _ <- cache.lookupOrAutoFetch("Foo", count.get, autoFetchEvery(1.second))
        _ <- count.update(_ + 1)
        _ <- timer.sleep(500.milliseconds)
        value <- cache.lookup("Foo")
      } yield value

      setup.unsafeRunSync must_=== Some(0)
    }

    "returns None if item is expired before auto refresh" in {
      val setup = for {
        count <- Ref.of[IO, Int](0)
        cache <- AutoFetchingCacheB.createCache[IO, String, Int](
          expiresIn(1.second))
        _ <- cache.lookupOrAutoFetch("Foo", count.get, autoFetchEvery(2.second))
        _ <- timer.sleep(1500.milliseconds)
        value <- cache.lookup("Foo")
      } yield value

      setup.unsafeRunSync must_=== None

    }

    "keeps from expiration through by auto refresh" in {
      val setup = for {
        count <- Ref.of[IO, Int](0)
        cache <- AutoFetchingCacheB.createCache[IO, String, Int](
          expiresIn(1.second) )
        _ <- cache.lookupOrAutoFetch("Foo", count.get, autoFetchEvery(500.milliseconds))
        _ <- count.update(_ + 1)
        _ <- timer.sleep(1500.milliseconds)
        value <- cache.lookup("Foo")
      } yield value

      setup.unsafeRunSync must_=== Some(1)
    }

//
//    "auto refresh turns off after deletion" in {
//      val setup = for {
//        count <- Ref.of[IO, Int](0)
//        cache <- AutoFetchingCacheB.createCache[IO, String, Int](
//          expiresIn(1.second))
//        _ <- cache.lookupOrAutoFetch("Foo", count.update(_ +1 ).as(1), autoFetchEvery(2.second))
//        _ <- timer.sleep(2500.milliseconds)
//        value <- cache.lookup("Foo")
//        cvalue <- count.get
//      } yield (value, cvalue)
//
//      val (value, cvalue) = setup.unsafeRunSync
//
//      value must_=== None
//      cvalue must_=== 1
//    }
//
//
//    "refetch value after autoReload timeout" in {
//      val setup = for {
//        count <- Ref.of[IO, Int](0)
//
//        cache <- AutoFetchingCache.createCache[IO, String, Int](None,
//          AutoFetchingCacheB.RefreshConfig(TimeSpec.unsafeFromDuration(500.milliseconds)))(_ =>
//          count.update( _ + 1).as(1)
//        )
//        _ <- cache.lookupCurrent("Foo")
//        _ <- timer.sleep(2.seconds)
//        value <- cache.lookupCurrent("Foo")
//        cValue <- count.get
//
//      } yield (cValue, value)
//
//      val (cValue, value) = setup.unsafeRunSync
//      (value must_=== 1).and(cValue >= 4)
//    }
//
//    "refetch value after autoReload timeout and before default expiration" in {
//      val setup = for {
//        count <- Ref.of[IO, Int](0)
//
//        cache <- AutoFetchingCache.createCache[IO, String, Int](
//          TimeSpec.fromDuration(3.second),
//          Some(AutoFetchingCache.RefreshConfig(TimeSpec.unsafeFromDuration(500.milliseconds))))(_ =>
//          count.update( _ + 1) *> count.get
//        )
//        _ <- cache.lookupCurrent("Foo")
//        _ <- timer.sleep(2.seconds)
//        value <- cache.lookupCurrent("Foo")
//        cValue <- count.get
//
//      } yield (cValue, value)
//
//      val (cValue, value) = setup.unsafeRunSync
//      (value must be >= 4).and(cValue >= 4)
//    }

  }
}