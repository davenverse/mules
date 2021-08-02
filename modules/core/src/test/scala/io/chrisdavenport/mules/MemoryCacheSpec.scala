package io.chrisdavenport.mules

import cats.effect.laws.util.TestContext
import scala.concurrent.duration._
import cats.effect._
import cats.effect.concurrent._
import munit._

class MemoryCacheSpec extends CatsEffectSuite {
  test("MemoryCache.ofSingleImmutableMap should get a value in a quicker period than the timeout") {
    val ctx = TestContext()
    implicit val testTimer: Timer[IO] = ctx.timer[IO]
    for {
      cache <- MemoryCache.ofSingleImmutableMap[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)))(Sync[IO], testTimer.clock)
      _ <- cache.insert("Foo", 1)
      _ = ctx.tick(1.nano)
      value <- cache.lookup("Foo")
    } yield {
      assertEquals(value, Some(1))
    }
  }

  test("MemoryCache.ofSingleImmutableMap should remove a value after delete") {
    val ctx = TestContext()
    implicit val testTimer: Timer[IO] = ctx.timer[IO]
    for {
      cache <- MemoryCache.ofSingleImmutableMap[IO, String, Int](None)(Sync[IO], testTimer.clock)
      _ <- cache.insert("Foo", 1)
      _ <- cache.delete("Foo")
      value <- cache.lookup("Foo")
    } yield {
      assertEquals(value, None)
    }
  }

  test("MemoryCache.ofSingleImmutableMap should remove a value in mass delete") {
    val ctx = TestContext()
    implicit val testTimer: Timer[IO] = ctx.timer[IO]
    for {
      cache <- MemoryCache.ofSingleImmutableMap[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)))(Sync[IO], testTimer.clock)
      _ <- cache.insert("Foo", 1)
      _ <- Sync[IO].delay(ctx.tick(2.seconds))
      _ <- cache.purgeExpired
      value <- cache.lookupNoUpdate("Foo")
    } yield {
      assertEquals(value, None)
    }
  }

  test("MemoryCache.ofSingleImmutableMap should lookup after interval fails to get a value") {
    val ctx = TestContext()
    implicit val testTimer: Timer[IO] = ctx.timer[IO]
    for {
      cache <- MemoryCache.ofSingleImmutableMap[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)))(Sync[IO], testTimer.clock)
      _ <- cache.insert("Foo", 1)
      _ <- Sync[IO].delay(ctx.tick(2.seconds))
      value <- cache.lookup("Foo")
    } yield {
      assertEquals(value, None)
    }
  }

  test("MemoryCache.ofSingleImmutableMap should not Remove an item on lookup No Delete") {
    val ctx = TestContext()
    implicit val testTimer: Timer[IO] = ctx.timer[IO]
    for {
      checkWasTouched <- Ref[IO].of(false)
      iCache <- MemoryCache.ofSingleImmutableMap[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)))(Sync[IO], testTimer.clock)
      cache = iCache.setOnDelete(_ => checkWasTouched.set(true))
      _ <- cache.insert("Foo", 1)
      _ <- Sync[IO].delay(ctx.tick(2.seconds))
      value <- cache.lookupNoUpdate("Foo")
      wasTouched <- checkWasTouched.get
    } yield {
      assertEquals(value, Option.empty[Int])
      assert(!wasTouched)
    }
  }

  test("MemoryCache.ofShardedImmutableMap should get a value in a quicker period than the timeout") {
    val ctx = TestContext()
    implicit val testTimer: Timer[IO] = ctx.timer[IO]
    for {
      cache <- MemoryCache.ofShardedImmutableMap[IO, String, Int](10, Some(TimeSpec.unsafeFromDuration(1.second)))(Sync[IO], testTimer.clock)
      _ <- cache.insert("Foo", 1)
      _ = ctx.tick(1.nano)
      value <- cache.lookup("Foo")
    } yield {
      assertEquals(value, Some(1))
    }
  }

  test("MemoryCache.ofShardedImmutableMap should remove a value after delete") {
    val ctx = TestContext()
    implicit val testTimer: Timer[IO] = ctx.timer[IO]
    for {
      cache <- MemoryCache.ofShardedImmutableMap[IO, String, Int](10, Some(TimeSpec.unsafeFromDuration(1.second)))(Sync[IO], testTimer.clock)
      _ <- cache.insert("Foo", 1)
      _ <- cache.delete("Foo")
      value <- cache.lookup("Foo")
    } yield {
      assertEquals(value, None)
    }
  }

  test("MemoryCache.ofShardedImmutableMap should remove a value in mass delete") {
    val ctx = TestContext()
    implicit val testTimer: Timer[IO] = ctx.timer[IO]
    for {
      cache <- MemoryCache.ofShardedImmutableMap[IO, String, Int](10, Some(TimeSpec.unsafeFromDuration(1.second)))(Sync[IO], testTimer.clock)
      _ <- cache.insert("Foo", 1)
      _ <- Sync[IO].delay(ctx.tick(2.seconds))
      _ <- cache.purgeExpired
      value <- cache.lookupNoUpdate("Foo")
    } yield {
      assertEquals(value, None)
    }
  }

  test("MemoryCache.ofShardedImmutableMap should lookup after interval fails to get a value") {
    val ctx = TestContext()
    implicit val testTimer: Timer[IO] = ctx.timer[IO]
    for {
      cache <- MemoryCache.ofShardedImmutableMap[IO, String, Int](10, Some(TimeSpec.unsafeFromDuration(1.second)))(Sync[IO], testTimer.clock)
      _ <- cache.insert("Foo", 1)
      _ <- Sync[IO].delay(ctx.tick(2.seconds))
      value <- cache.lookup("Foo")
    } yield {
      assertEquals(value, None)
    }
  }

  test("MemoryCache.ofShardedImmutableMap should not Remove an item on lookup No Delete") {
    val ctx = TestContext()
    implicit val testTimer: Timer[IO] = ctx.timer[IO]
    for {
      checkWasTouched <- Ref[IO].of(false)
      iCache <- MemoryCache.ofShardedImmutableMap[IO, String, Int](10, Some(TimeSpec.unsafeFromDuration(1.second)))(Sync[IO], testTimer.clock)
      cache = iCache.setOnDelete(_ => checkWasTouched.set(true))
      _ <- cache.insert("Foo", 1)
      _ <- Sync[IO].delay(ctx.tick(2.seconds))
      value <- cache.lookupNoUpdate("Foo")
      wasTouched <- checkWasTouched.get
    } yield {
      assertEquals(value, Option.empty)
      assert(!wasTouched)
    }
  }

  test("MemoryCache.ofConcurrentHashMap should get a value in a quicker period than the timeout") {
    val ctx = TestContext()
    implicit val testTimer: Timer[IO] = ctx.timer[IO]
    for {
      cache <- MemoryCache.ofConcurrentHashMap[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)))(Sync[IO], testTimer.clock)
      _ <- cache.insert("Foo", 1)
      _ = ctx.tick(1.nano)
      value <- cache.lookup("Foo")
    } yield {
      assertEquals(value, Some(1))
    }
  }

  test("MemoryCache.ofConcurrentHashMap should remove a value after delete") {
    val ctx = TestContext()
    implicit val testTimer: Timer[IO] = ctx.timer[IO]
    for {
      cache <- MemoryCache.ofConcurrentHashMap[IO, String, Int](None)(Sync[IO], testTimer.clock)
      _ <- cache.insert("Foo", 1)
      _ <- cache.delete("Foo")
      value <- cache.lookup("Foo")
    } yield {
      assertEquals(value, None)
    }
  }

  test("MemoryCache.ofConcurrentHashMap should Remove a value in mass delete") {
    val ctx = TestContext()
    implicit val testTimer: Timer[IO] = ctx.timer[IO]
    for {
      cache <- MemoryCache.ofConcurrentHashMap[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)))(Sync[IO], testTimer.clock)
      _ <- cache.insert("Foo", 1)
      _ <- Sync[IO].delay(ctx.tick(2.seconds))
      _ <- cache.purgeExpired
      value <- cache.lookupNoUpdate("Foo")
    } yield {
      assertEquals(value, None)
    }
  }

  test("MemoryCache.ofConcurrentHashMap should Lookup after interval fails to get a value") {
    val ctx = TestContext()
    implicit val testTimer: Timer[IO] = ctx.timer[IO]
    for {
      cache <- MemoryCache.ofConcurrentHashMap[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)))(Sync[IO], testTimer.clock)
      _ <- cache.insert("Foo", 1)
      _ <- Sync[IO].delay(ctx.tick(2.seconds))
      value <- cache.lookup("Foo")
    } yield {
      assertEquals(value, None)
    }
  }

  test("MemoryCache.ofConcurrentHashMap should Not Remove an item on lookup No Delete") {
    val ctx = TestContext()
    implicit val testTimer: Timer[IO] = ctx.timer[IO]
    for {
      checkWasTouched <- Ref[IO].of(false)
      iCache <- MemoryCache.ofConcurrentHashMap[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)))(Sync[IO], testTimer.clock)
      cache = iCache.setOnDelete(_ => checkWasTouched.set(true))
      _ <- cache.insert("Foo", 1)
      _ <- Sync[IO].delay(ctx.tick(2.seconds))
      value <- cache.lookupNoUpdate("Foo")
      wasTouched <- checkWasTouched.get
    } yield {
      assertEquals(value, Option.empty[Int])
      assert(!wasTouched)
    }
  }
}
