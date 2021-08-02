package io.chrisdavenport.mules
package reload

import cats.effect._
import cats.effect.concurrent.Ref
import cats.syntax.all._
import munit._

import scala.concurrent.duration._

class AutoFetchingCacheSpec extends CatsEffectSuite {
  test("AutoFetchingCache should get a value in a quicker period than the timeout") {
    for {
      count <- Ref.of[IO, Int](0)
      cache <- AutoFetchingCache.createCache[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)), None)(_ =>
        count.update( _ + 1).as(1)
      )
      value <- cache.lookupCurrent("Foo")
      cValue <- count.get
    } yield {
      assertEquals(cValue, 1)
      assertEquals(value, 1)
    }
  }

  test("AutoFetchingCache should refetch value after expiration timeout") {
    for {
      count <- Ref.of[IO, Int](0)

      cache <- AutoFetchingCache.createCache[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)), None)(_ =>
        count.update( _ + 1).as(1)
      )
      _ <- cache.lookupCurrent("Foo")
      _ <- Timer[IO].sleep(2.seconds)
      value <- cache.lookupCurrent("Foo")
      cValue <- count.get
    } yield {
      assertEquals(cValue, 2)
      assertEquals(value, 1)
    }
  }

  test("AutoFetchingCache should refetch value after autoReload timeout") {
    for {
      count <- Ref.of[IO, Int](0)

      cache <- AutoFetchingCache.createCache[IO, String, Int](None, Some(AutoFetchingCache.RefreshConfig(TimeSpec.unsafeFromDuration(500.milliseconds))))(_ =>
        count.update( _ + 1).as(1)
      )
      _ <- cache.lookupCurrent("Foo")
      _ <- Timer[IO].sleep(2.seconds)
      value <- cache.lookupCurrent("Foo")
      cValue <- count.get

    } yield {
      assertEquals(value, 1)
      assert(cValue >= 4)
    }
  }

  test("AutoFetchingCache should refetch value after autoReload timeout and before default expiration") {
    for {
      count <- Ref.of[IO, Int](0)

      cache <- AutoFetchingCache.createCache[IO, String, Int](
        TimeSpec.fromDuration(3.second),
        Some(AutoFetchingCache.RefreshConfig(TimeSpec.unsafeFromDuration(500.milliseconds))))(_ =>
        count.update( _ + 1) *> count.get
      )
      _ <- cache.lookupCurrent("Foo")
      _ <- Timer[IO].sleep(2.seconds)
      value <- cache.lookupCurrent("Foo")
      cValue <- count.get

    } yield {
      assert(value >= 4)
      assert(cValue >= 4)
    }
  }
}
