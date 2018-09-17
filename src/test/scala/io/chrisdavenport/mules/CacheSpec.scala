package io.chrisdavenport.mules

import org.specs2.mutable.Specification
import scala.concurrent.duration._
import cats.effect._
// import cats.effect.implicits._
import cats.effect.IO
import scala.concurrent.ExecutionContext.Implicits.global

class CacheSpec extends Specification {

  "Cache" should {
    "get a value in a quicker period than the timeout" in {
      val setup = for {
        cache <- Cache.createCache[IO, String, Int](Some(Cache.TimeSpec.unsafeFromDuration(1.second)))
        _ <- cache.insert("Foo", 1)
        value <- cache.lookup("Foo")
      } yield value
      setup.unsafeRunSync must_=== Some(1)
    }

    "remove a value after delete" in {
      val setup = for {
        cache <- Cache.createCache[IO, String, Int](None)
        _ <- cache.insert("Foo", 1)
        _ <- cache.delete("Foo")
        value <- cache.lookup("Foo")
      } yield value
      setup.unsafeRunSync must_=== None
    }

    "Remove a value in mass delete" in {
      val setup = for {
        cache <- Cache.createCache[IO, String, Int](Some(Cache.TimeSpec.unsafeFromDuration(1.second)))
        _ <- cache.insert("Foo", 1)
        _ <- Sync[IO].delay(Thread.sleep(2000))
        _ <- cache.purgeExpired
        value <- cache.lookupNoUpdate("Foo")
      } yield value
      setup.unsafeRunSync must_=== None
    }

    "Lookup after interval fails to get a value" in {
      val setup = for {
        cache <- Cache.createCache[IO, String, Int](Some(Cache.TimeSpec.unsafeFromDuration(1.second)))
        _ <- cache.insert("Foo", 1)
        _ <- Sync[IO].delay(Thread.sleep(2000))
        value <- cache.lookup("Foo")
      } yield value
      setup.unsafeRunSync must_=== None
    }
  }
}