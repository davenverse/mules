package io.chrisdavenport.mules

import cats.effect.IO
import cats.effect.concurrent.Ref
import cats.syntax.functor._
import io.chrisdavenport.mules.AutoFetchingCache.Refresh
import org.specs2.mutable.Specification

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

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
        value <- cache.lookup("Foo")
        cValue <- count.get
      } yield (cValue, value)
      setup.unsafeRunSync must_=== ((1, 1))
    }


    "refetch value after expiration timeout" in {
      val setup = for {
        count <- Ref.of[IO, Int](0)

        cache <- AutoFetchingCache.createCache[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)), None)(_ =>
          count.update( _ + 1).as(1)
         )
        _ <- cache.lookup("Foo")
        _ <- timer.sleep(2.seconds)
        value <- cache.lookup("Foo")
        cValue <- count.get

      } yield (cValue, value)
      setup.unsafeRunSync must_=== ((2, 1))
    }


    "refetch value after autoReload timeout" in {
      val setup = for {
        count <- Ref.of[IO, Int](0)

        cache <- AutoFetchingCache.createCache[IO, String, Int](None, Some(Refresh.Config(TimeSpec.unsafeFromDuration(500.milliseconds))))(_ =>
          count.update( _ + 1).as(1)
        )
        _ <- cache.lookup("Foo")
        _ <- timer.sleep(2.seconds)
        value <- cache.lookup("Foo")
        cValue <- count.get

      } yield (cValue, value)

      val (cValue, value) = setup.unsafeRunSync
      (value must_=== 1).and(cValue >= 4)
    }

  }
}