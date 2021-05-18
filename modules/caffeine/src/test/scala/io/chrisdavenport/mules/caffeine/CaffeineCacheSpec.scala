package io.chrisdavenport.mules.caffeine

import scala.concurrent.duration._
import cats.effect._
import munit._
import io.chrisdavenport.mules.TimeSpec

class CaffeineCacheSpec extends CatsEffectSuite {
  test("CaffeineCache should get a value in a quicker period than the timeout") {
    for {
      cache <- CaffeineCache.build[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)), None, None)
      _ <- cache.insert("Foo", 1)
      _ <- Temporal[IO].sleep(1.milli)
      value <- cache.lookup("Foo")
    } yield {
      assertEquals(value, Some(1))
    }
  }


  test("CaffeineCache should remove a value after delete") {
    for {
      cache <- CaffeineCache.build[IO, String, Int](None, None, None)
      _ <- cache.insert("Foo", 1)
      _ <- cache.delete("Foo")
      value <- cache.lookup("Foo")
    } yield {
      assertEquals(value, None)
    }
  }


  test("CaffeineCache should Lookup after interval fails to get a value") {
    for {
      cache <- CaffeineCache.build[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)), None, None)
      _ <- cache.insert("Foo", 1)
      _ <- Temporal[IO].sleep(2.second)
      value <- cache.lookup("Foo")
    } yield {
      assertEquals(value, None)
    }
  }
}
