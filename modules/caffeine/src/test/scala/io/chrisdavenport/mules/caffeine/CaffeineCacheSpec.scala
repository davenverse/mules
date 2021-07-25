package io.chrisdavenport.mules.caffeine

import org.specs2.mutable.Specification
import scala.concurrent.duration._
import cats.effect._
// import cats.effect.implicits._
import cats.effect.IO
import cats.effect.testing.specs2.CatsIO
import io.chrisdavenport.mules.TimeSpec
import cats.effect.Temporal

class CaffeineCacheSpec extends Specification with CatsIO {
  "CaffeineCache" should {
    "get a value in a quicker period than the timeout" in {
      val setup = for {
        cache <- CaffeineCache.build[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)), None, None)
        _ <- cache.insert("Foo", 1)
        _ <- Temporal[IO].sleep(1.milli)
        value <- cache.lookup("Foo")
      } yield value
      setup.map(_ must_=== Some(1))
    }


    "remove a value after delete" in {
      val setup = for {
        cache <- CaffeineCache.build[IO, String, Int](None, None, None)
        _ <- cache.insert("Foo", 1)
        _ <- cache.delete("Foo")
        value <- cache.lookup("Foo")
      } yield value
      setup.map(_ must_=== None)
    }


    "Lookup after interval fails to get a value" in {
      val setup = for {
        cache <- CaffeineCache.build[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)), None, None)
        _ <- cache.insert("Foo", 1)
        _ <- Temporal[IO].sleep(2.second)
        value <- cache.lookup("Foo")
      } yield value
      setup.map(_ must_=== None)
    }


  }
}