package io.chrisdavenport.mules.reload

import cats.effect.IO
import cats.implicits._
import io.chrisdavenport.mules._
import org.specs2.mutable.Specification

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import cats.effect.Ref

class AutoFetchingCacheSpec extends Specification {

  implicit val ctx = IO.contextShift(ExecutionContext.global)
  implicit val timer = IO.timer(ExecutionContext.Implicits.global)

  "AutoFetchingCache" should {
    "get a value in a quicker period than the timeout" in {
      val setup = for {

        count <- Ref.of[IO, Int](0)

        cache <- AutoFetchingCache.createCache[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)), None)(_ =>
          count.update( _ + 1).as(1)
        )
        value <- cache.lookupCurrent("Foo")
        cValue <- count.get
      } yield (cValue, value)
      setup.unsafeRunSync() must_=== ((1, 1))
    }


    "refetch value after expiration timeout" in {
      val setup = for {
        count <- Ref.of[IO, Int](0)

        cache <- AutoFetchingCache.createCache[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)), None)(_ =>
          count.update( _ + 1).as(1)
        )
        _ <- cache.lookupCurrent("Foo")
        _ <- timer.sleep(2.seconds)
        value <- cache.lookupCurrent("Foo")
        cValue <- count.get

      } yield (cValue, value)
      setup.unsafeRunSync() must_=== ((2, 1))
    }


    "refetch value after autoReload timeout" in {
      val setup = for {
        count <- Ref.of[IO, Int](0)

        cache <- AutoFetchingCache.createCache[IO, String, Int](None, Some(AutoFetchingCache.RefreshConfig(TimeSpec.unsafeFromDuration(500.milliseconds))))(_ =>
          count.update( _ + 1).as(1)
        )
        _ <- cache.lookupCurrent("Foo")
        _ <- timer.sleep(2.seconds)
        value <- cache.lookupCurrent("Foo")
        cValue <- count.get

      } yield (cValue, value)

      val (cValue, value) = setup.unsafeRunSync()
      (value must_=== 1).and(cValue >= 4)
    }

    "refetch value after autoReload timeout and before default expiration" in {
      val setup = for {
        count <- Ref.of[IO, Int](0)

        cache <- AutoFetchingCache.createCache[IO, String, Int](
          TimeSpec.fromDuration(3.second),
          Some(AutoFetchingCache.RefreshConfig(TimeSpec.unsafeFromDuration(500.milliseconds))))(_ =>
          count.update( _ + 1) *> count.get
        )
        _ <- cache.lookupCurrent("Foo")
        _ <- timer.sleep(2.seconds)
        value <- cache.lookupCurrent("Foo")
        cValue <- count.get

      } yield (cValue, value)

      val (cValue, value) = setup.unsafeRunSync()
      (value must be >= 4).and(cValue >= 4)
    }

  }
}
