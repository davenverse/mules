package io.chrisdavenport.mules

import cats.effect.laws.util.TestContext
import cats.effect._
import cats.syntax.all._
import munit._

import scala.concurrent.duration._

class AutoMemoryCacheSpec extends CatsEffectSuite {
  val cacheKeyExpiration    = TimeSpec.unsafeFromDuration(12.hours)
  val checkExpirationsEvery = TimeSpec.unsafeFromDuration(10.millis)

  private val ctx = TestContext()

  implicit override def munitContextShift: ContextShift[IO] =
    IO.contextShift(ctx)
  implicit override def munitTimer: Timer[IO] =
    ctx.timer

  test("Auto MemoryCache.ofSingleImmutableMap should expire keys") {
    Resource.eval(MemoryCache.ofSingleImmutableMap[IO, Int, String](cacheKeyExpiration.some))
      .flatMap(cache => MemoryCache.liftToAuto(cache, checkExpirationsEvery).as(cache))
      .use(cache =>
        for {
          _ <- cache.insert(1, "foo")
          _ <- IO(ctx.tick(5.hours))
          _ <- cache.insert(2, "bar")
          a1 <- cache.lookupNoUpdate(1)
          b1 <- cache.lookupNoUpdate(2)
          _ <- IO(ctx.tick(7.hours + 1.second)) // expiration time reached
          a2 <- cache.lookupNoUpdate(1)
          b2 <- cache.lookupNoUpdate(2)
        } yield {
          assert(a1.contains("foo"))
          assert(b1.contains("bar"))
          assertEquals(a2, None) // not here
          assert(b2.contains("bar"))
        }
      )
  }

  test("Auto MemoryCache.ofSingleImmutableMap should resets expiration") {
    Resource.eval(MemoryCache.ofSingleImmutableMap[IO, Int, String](cacheKeyExpiration.some))
      .flatMap(cache => MemoryCache.liftToAuto(cache, checkExpirationsEvery).as(cache))
      .use(cache =>
      for {
        _ <- cache.insert(1, "foo")
        _ <- IO(ctx.tick(5.hours))
        a1 <- cache.lookupNoUpdate(1)
        _ <- cache.insert(1, "bar")
        _ <- IO(ctx.tick(7.hours + 1.second)) // expiration time reached for first timestamp
        a2 <- cache.lookupNoUpdate(1)
        _ <- IO(ctx.tick(5.hours)) // expiration time reached for last timestamp
        a3 <- cache.lookupNoUpdate(1)
      } yield {
        assert(a1.contains("foo"))
        assert(a2.contains("bar"))
        assert(a3.isEmpty)
      })
  }

  test("Auto MemoryCache.ofConcurrentHashMap should expire keys") {
    Resource.eval(MemoryCache.ofConcurrentHashMap[IO, Int, String](cacheKeyExpiration.some))
      .flatMap(cache => MemoryCache.liftToAuto(cache, checkExpirationsEvery).as(cache))
      .use(cache =>
        for {
          _ <- cache.insert(1, "foo")
          _ <- IO(ctx.tick(5.hours))
          _ <- cache.insert(2, "bar")
          a1 <- cache.lookupNoUpdate(1)
          b1 <- cache.lookupNoUpdate(2)
          _ <- IO(ctx.tick(7.hours + 1.second)) // expiration time reached
          a2 <- cache.lookupNoUpdate(1)
          b2 <- cache.lookupNoUpdate(2)
        } yield {
          assert(a1.contains("foo"))
          assert(b1.contains("bar"))
          assertEquals(a2, None) // not here
          assert(b2.contains("bar"))
        }
      )
  }

  test("Auto MemoryCache.ofConcurrentHashMap should resets expiration") {
    Resource.eval(MemoryCache.ofConcurrentHashMap[IO, Int, String](cacheKeyExpiration.some))
      .flatMap(cache => MemoryCache.liftToAuto(cache, checkExpirationsEvery).as(cache))
      .use(cache =>
      for {
        _ <- cache.insert(1, "foo")
        _ <- IO(ctx.tick(5.hours))
        a1 <- cache.lookupNoUpdate(1)
        _ <- cache.insert(1, "bar")
        _ <- IO(ctx.tick(7.hours + 1.second)) // expiration time reached for first timestamp
        a2 <- cache.lookupNoUpdate(1)
        _ <- IO(ctx.tick(5.hours)) // expiration time reached for last timestamp
        a3 <- cache.lookupNoUpdate(1)
      } yield {
        assert(a1.contains("foo"))
        assert(a2.contains("bar"))
        assertEquals(a3, None)
      }
    )
  }
}
