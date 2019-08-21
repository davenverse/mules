package io.chrisdavenport.mules

import org.specs2.mutable.Specification
import scala.concurrent.duration._
import cats.effect._
import cats.effect.concurrent._
// import cats.effect.implicits._
import cats.effect.IO
import cats.effect.laws.util.TestContext

class MemoryCacheSpec extends Specification {


  "MemoryCache" should {
    "get a value in a quicker period than the timeout" in {
      val ctx = TestContext()
      implicit val testTimer: Timer[IO] = ctx.timer[IO]
      val setup = for {
        cache <- MemoryCache.createMemoryCache[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)))
        _ <- cache.insert("Foo", 1)
        _ = ctx.tick(1.nano)
        value <- cache.lookup("Foo")
      } yield value
      setup.unsafeRunSync must_=== Some(1)
    }

    "remove a value after delete" in {
      val ctx = TestContext()
      implicit val testTimer: Timer[IO] = ctx.timer[IO]
      val setup = for {
        cache <- MemoryCache.createMemoryCache[IO, String, Int](None)
        _ <- cache.insert("Foo", 1)
        _ <- cache.delete("Foo")
        value <- cache.lookup("Foo")
      } yield value
      setup.unsafeRunSync must_=== None
    }

    "Remove a value in mass delete" in {
      val ctx = TestContext()
      implicit val testTimer: Timer[IO] = ctx.timer[IO]
      val setup = for {
        cache <- MemoryCache.createMemoryCache[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)))
        _ <- cache.insert("Foo", 1)
        _ <- Sync[IO].delay(ctx.tick(2.seconds))
        _ <- cache.purgeExpired
        value <- cache.lookupNoUpdate("Foo")
      } yield value
      setup.unsafeRunSync must_=== None
    }

    "Lookup after interval fails to get a value" in {
      val ctx = TestContext()
      implicit val testTimer: Timer[IO] = ctx.timer[IO]
      val setup = for {
        cache <- MemoryCache.createMemoryCache[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)))
        _ <- cache.insert("Foo", 1)
        _ <- Sync[IO].delay(ctx.tick(2.seconds))
        value <- cache.lookup("Foo")
      } yield value
      setup.unsafeRunSync must_=== None
    }

    "Not Remove an item on lookup No Delete" in {
      val ctx = TestContext()
      implicit val testTimer: Timer[IO] = ctx.timer[IO]
      val setup = for {
        checkWasTouched <- Ref[IO].of(false)
        iCache <- MemoryCache.createMemoryCache[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)))
        cache = iCache.setOnDelete(_ => checkWasTouched.set(true))
        _ <- cache.insert("Foo", 1)
        _ <- Sync[IO].delay(ctx.tick(2.seconds))
        value <- cache.lookupNoUpdate("Foo")
        wasTouched <- checkWasTouched.get
      } yield (value, wasTouched)
      setup.unsafeRunSync.must_===((Option.empty[Int], false))
    }
  }
}