package io.chrisdavenport.mules.caffeine

import org.specs2.mutable.Specification
import scala.concurrent.duration._
import cats.effect._
// import cats.effect.implicits._
import cats.effect.IO
import cats.effect.testing.specs2.CatsIO
import io.chrisdavenport.mules.TimeSpec

class CaffeineCacheSpec extends Specification with CatsIO {
  "CaffeineCache" should {
    "get a value in a quicker period than the timeout" in {
      val setup = for {
        cache <- CaffeineCacheBuilder.empty[String, Int].withWriteTimeout(TimeSpec.unsafeFromDuration(1.second)).buildCache[IO]
        _ <- cache.insert("Foo", 1)
        _ <- Timer[IO].sleep(1.milli)
        value <- cache.lookup("Foo")
      } yield value
      setup.map(_ must_=== Some(1))
    }


    "remove a value after delete" in {
      val setup = for {
        cache <- CaffeineCacheBuilder.empty[String, Int].buildCache[IO]
        _ <- cache.insert("Foo", 1)
        _ <- cache.delete("Foo")
        value <- cache.lookup("Foo")
      } yield value
      setup.map(_ must_=== None)
    }


    "Lookup after interval fails to get a value" in {
      val setup = for {
        cache <- CaffeineCacheBuilder.empty[String, Int].withWriteTimeout(TimeSpec.unsafeFromDuration(1.second)).buildCache[IO]
        _ <- cache.insert("Foo", 1)
        _ <- Timer[IO].sleep(2.second)
        value <- cache.lookup("Foo")
      } yield value
      setup.map(_ must_=== None)
    }
  }
}
