package io.chrisdavenport.mules

import org.specs2.mutable.Specification
import scala.concurrent.duration._
import cats.effect._
import cats.effect.concurrent._
// import cats.effect.implicits._
import cats.effect.IO
import cats.effect.laws.util.TestContext
import cats.effect.testing.specs2.CatsIO
import cats.effect.{ Ref, Temporal }

class MemoryCacheSpec extends Specification with CatsIO {


  "MemoryCache.ofSingleImmutableMap" should {
    "get a value in a quicker period than the timeout" in {
      val ctx = TestContext()
      implicit val testTimer: Temporal[IO] = ctx.timer[IO]
      val setup = for {
        cache <- MemoryCache.ofSingleImmutableMap[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)))(Sync[IO], testTimer.clock)
        _ <- cache.insert("Foo", 1)
        _ = ctx.tick(1.nano)
        value <- cache.lookup("Foo")
      } yield value
      setup.map(_ must_=== Some(1))
    }

    "remove a value after delete" in {
      val ctx = TestContext()
      implicit val testTimer: Temporal[IO] = ctx.timer[IO]
      val setup = for {
        cache <- MemoryCache.ofSingleImmutableMap[IO, String, Int](None)(Sync[IO], testTimer.clock)
        _ <- cache.insert("Foo", 1)
        _ <- cache.delete("Foo")
        value <- cache.lookup("Foo")
      } yield value
      setup.map(_ must_=== None)
    }

    "Remove a value in mass delete" in {
      val ctx = TestContext()
      implicit val testTimer: Temporal[IO] = ctx.timer[IO]
      val setup = for {
        cache <- MemoryCache.ofSingleImmutableMap[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)))(Sync[IO], testTimer.clock)
        _ <- cache.insert("Foo", 1)
        _ <- Sync[IO].delay(ctx.tick(2.seconds))
        _ <- cache.purgeExpired
        value <- cache.lookupNoUpdate("Foo")
      } yield value
      setup.map(_ must_=== None)
    }

    "Lookup after interval fails to get a value" in {
      val ctx = TestContext()
      implicit val testTimer: Temporal[IO] = ctx.timer[IO]
      val setup = for {
        cache <- MemoryCache.ofSingleImmutableMap[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)))(Sync[IO], testTimer.clock)
        _ <- cache.insert("Foo", 1)
        _ <- Sync[IO].delay(ctx.tick(2.seconds))
        value <- cache.lookup("Foo")
      } yield value
      setup.map(_ must_=== None)
    }

    "Not Remove an item on lookup No Delete" in {
      val ctx = TestContext()
      implicit val testTimer: Temporal[IO] = ctx.timer[IO]
      val setup = for {
        checkWasTouched <- Ref[IO].of(false)
        iCache <- MemoryCache.ofSingleImmutableMap[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)))(Sync[IO], testTimer.clock)
        cache = iCache.setOnDelete(_ => checkWasTouched.set(true))
        _ <- cache.insert("Foo", 1)
        _ <- Sync[IO].delay(ctx.tick(2.seconds))
        value <- cache.lookupNoUpdate("Foo")
        wasTouched <- checkWasTouched.get
      } yield (value, wasTouched)
      setup.map(_.must_===((Option.empty[Int], false)))
    }
  }

  "MemoryCache.ofShardedImmutableMap" should {
    "get a value in a quicker period than the timeout" in {
      val ctx = TestContext()
      implicit val testTimer: Temporal[IO] = ctx.timer[IO]
      val setup = for {
        cache <- MemoryCache.ofShardedImmutableMap[IO, String, Int](10, Some(TimeSpec.unsafeFromDuration(1.second)))(Sync[IO], testTimer.clock)
        _ <- cache.insert("Foo", 1)
        _ = ctx.tick(1.nano)
        value <- cache.lookup("Foo")
      } yield value
      setup.map(_ must_=== Some(1))
    }

    "remove a value after delete" in {
      val ctx = TestContext()
      implicit val testTimer: Temporal[IO] = ctx.timer[IO]
      val setup = for {
        cache <- MemoryCache.ofShardedImmutableMap[IO, String, Int](10, Some(TimeSpec.unsafeFromDuration(1.second)))(Sync[IO], testTimer.clock)
        _ <- cache.insert("Foo", 1)
        _ <- cache.delete("Foo")
        value <- cache.lookup("Foo")
      } yield value
      setup.map(_ must_=== None)
    }

    "Remove a value in mass delete" in {
      val ctx = TestContext()
      implicit val testTimer: Temporal[IO] = ctx.timer[IO]
      val setup = for {
        cache <- MemoryCache.ofShardedImmutableMap[IO, String, Int](10, Some(TimeSpec.unsafeFromDuration(1.second)))(Sync[IO], testTimer.clock)
        _ <- cache.insert("Foo", 1)
        _ <- Sync[IO].delay(ctx.tick(2.seconds))
        _ <- cache.purgeExpired
        value <- cache.lookupNoUpdate("Foo")
      } yield value
      setup.map(_ must_=== None)
    }

    "Lookup after interval fails to get a value" in {
      val ctx = TestContext()
      implicit val testTimer: Temporal[IO] = ctx.timer[IO]
      val setup = for {
        cache <- MemoryCache.ofShardedImmutableMap[IO, String, Int](10, Some(TimeSpec.unsafeFromDuration(1.second)))(Sync[IO], testTimer.clock)
        _ <- cache.insert("Foo", 1)
        _ <- Sync[IO].delay(ctx.tick(2.seconds))
        value <- cache.lookup("Foo")
      } yield value
      setup.map(_ must_=== None)
    }

    "Not Remove an item on lookup No Delete" in {
      val ctx = TestContext()
      implicit val testTimer: Temporal[IO] = ctx.timer[IO]
      val setup = for {
        checkWasTouched <- Ref[IO].of(false)
        iCache <- MemoryCache.ofShardedImmutableMap[IO, String, Int](10, Some(TimeSpec.unsafeFromDuration(1.second)))(Sync[IO], testTimer.clock)
        cache = iCache.setOnDelete(_ => checkWasTouched.set(true))
        _ <- cache.insert("Foo", 1)
        _ <- Sync[IO].delay(ctx.tick(2.seconds))
        value <- cache.lookupNoUpdate("Foo")
        wasTouched <- checkWasTouched.get
      } yield (value, wasTouched)
      setup.map(_.must_===((Option.empty[Int], false)))
    }
  }

  "MemoryCache.ofConcurrentHashMap" should {
    "get a value in a quicker period than the timeout" in {
      val ctx = TestContext()
      implicit val testTimer: Temporal[IO] = ctx.timer[IO]
      val setup = for {
        cache <- MemoryCache.ofConcurrentHashMap[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)))(Sync[IO], testTimer.clock)
        _ <- cache.insert("Foo", 1)
        _ = ctx.tick(1.nano)
        value <- cache.lookup("Foo")
      } yield value
      setup.map(_ must_=== Some(1))
    }

    "remove a value after delete" in {
      val ctx = TestContext()
      implicit val testTimer: Temporal[IO] = ctx.timer[IO]
      val setup = for {
        cache <- MemoryCache.ofConcurrentHashMap[IO, String, Int](None)(Sync[IO], testTimer.clock)
        _ <- cache.insert("Foo", 1)
        _ <- cache.delete("Foo")
        value <- cache.lookup("Foo")
      } yield value
      setup.map(_ must_=== None)
    }

    "Remove a value in mass delete" in {
      val ctx = TestContext()
      implicit val testTimer: Temporal[IO] = ctx.timer[IO]
      val setup = for {
        cache <- MemoryCache.ofConcurrentHashMap[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)))(Sync[IO], testTimer.clock)
        _ <- cache.insert("Foo", 1)
        _ <- Sync[IO].delay(ctx.tick(2.seconds))
        _ <- cache.purgeExpired
        value <- cache.lookupNoUpdate("Foo")
      } yield value
      setup.map(_ must_=== None)
    }

    "Lookup after interval fails to get a value" in {
      val ctx = TestContext()
      implicit val testTimer: Temporal[IO] = ctx.timer[IO]
      val setup = for {
        cache <- MemoryCache.ofConcurrentHashMap[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)))(Sync[IO], testTimer.clock)
        _ <- cache.insert("Foo", 1)
        _ <- Sync[IO].delay(ctx.tick(2.seconds))
        value <- cache.lookup("Foo")
      } yield value
      setup.map(_ must_=== None)
    }

    "Not Remove an item on lookup No Delete" in {
      val ctx = TestContext()
      implicit val testTimer: Temporal[IO] = ctx.timer[IO]
      val setup = for {
        checkWasTouched <- Ref[IO].of(false)
        iCache <- MemoryCache.ofConcurrentHashMap[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)))(Sync[IO], testTimer.clock)
        cache = iCache.setOnDelete(_ => checkWasTouched.set(true))
        _ <- cache.insert("Foo", 1)
        _ <- Sync[IO].delay(ctx.tick(2.seconds))
        value <- cache.lookupNoUpdate("Foo")
        wasTouched <- checkWasTouched.get
      } yield (value, wasTouched)
      setup.map(_.must_===((Option.empty[Int], false)))
    }
  }
}