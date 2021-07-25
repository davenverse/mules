package io.chrisdavenport.mules

import cats.effect.laws.util.TestContext
import cats.effect._
import cats.implicits._
import org.specs2.mutable.Specification

import scala.concurrent.duration._
import cats.effect.Temporal

class AutoMemoryCacheSpec extends Specification {

  val cacheKeyExpiration    = TimeSpec.unsafeFromDuration(12.hours)
  val checkExpirationsEvery = TimeSpec.unsafeFromDuration(10.millis)

  "Auto MemoryCache.ofSingleImmutableMap" should {

    "expire keys" in WithTestContext { ctx => implicit cs => implicit timer =>
      val spec = Resource.eval(MemoryCache.ofSingleImmutableMap[IO, Int, String](cacheKeyExpiration.some))
        .flatMap(cache => MemoryCache.liftToAuto(cache, checkExpirationsEvery).as(cache))
        .use(cache =>
          for {
            _ <- cache.insert(1, "foo")
            _ <- IO(ctx.tick(5.hours))
            _ <- cache.insert(2, "bar")
            a1 <- cache.lookupNoUpdate(1)
            b1 <- cache.lookupNoUpdate(2)
            _ <- IO {
                  assert(a1.contains("foo"))
                  assert(b1.contains("bar"))
                }
            _ <- IO(ctx.tick(7.hours + 1.second)) // expiration time reached
            a2 <- cache.lookupNoUpdate(1)
            b2 <- cache.lookupNoUpdate(2)
            _ <- IO {
                  assert(a2.isEmpty) // not here
                  assert(b2.contains("bar"))
                }
          } yield ()
        )
      spec.as(1).unsafeRunSync() must_== 1
    }

    "resets expiration" in WithTestContext { ctx => implicit cs => implicit timer =>
      val spec = Resource.eval(MemoryCache.ofSingleImmutableMap[IO, Int, String](cacheKeyExpiration.some))
        .flatMap(cache => MemoryCache.liftToAuto(cache, checkExpirationsEvery).as(cache))
        .use(cache =>
        for {
          _ <- cache.insert(1, "foo")
          _ <- IO(ctx.tick(5.hours))
          a1 <- cache.lookupNoUpdate(1)
          _ <- IO { assert(a1.contains("foo")) }
          _ <- cache.insert(1, "bar")
          _ <- IO(ctx.tick(7.hours + 1.second)) // expiration time reached for first timestamp
          a2 <- cache.lookupNoUpdate(1)
          _ <- IO { assert(a2.contains("bar")) }
          _ <- IO(ctx.tick(5.hours)) // expiration time reached for last timestamp
          a3 <- cache.lookupNoUpdate(1)
          _ <- IO { assert(a3.isEmpty) }
        } yield ()
      )
      spec.as(1).unsafeRunSync() must_== 1
    }

  }

  "Auto MemoryCache.ofConcurrentHashMap" should {

    "expire keys" in WithTestContext { ctx => implicit cs => implicit timer =>
      val spec = Resource.eval(MemoryCache.ofConcurrentHashMap[IO, Int, String](cacheKeyExpiration.some))
        .flatMap(cache => MemoryCache.liftToAuto(cache, checkExpirationsEvery).as(cache))
        .use(cache =>
          for {
            _ <- cache.insert(1, "foo")
            _ <- IO(ctx.tick(5.hours))
            _ <- cache.insert(2, "bar")
            a1 <- cache.lookupNoUpdate(1)
            b1 <- cache.lookupNoUpdate(2)
            _ <- IO {
                  assert(a1.contains("foo"))
                  assert(b1.contains("bar"))
                }
            _ <- IO(ctx.tick(7.hours + 1.second)) // expiration time reached
            a2 <- cache.lookupNoUpdate(1)
            b2 <- cache.lookupNoUpdate(2)
            _ <- IO {
                  assert(a2.isEmpty) // not here
                  assert(b2.contains("bar"))
                }
          } yield ()
        )
      spec.as(1).unsafeRunSync() must_== 1
    }

    "resets expiration" in WithTestContext { ctx => implicit cs => implicit timer =>
      val spec = Resource.eval(MemoryCache.ofConcurrentHashMap[IO, Int, String](cacheKeyExpiration.some))
        .flatMap(cache => MemoryCache.liftToAuto(cache, checkExpirationsEvery).as(cache))
        .use(cache =>
        for {
          _ <- cache.insert(1, "foo")
          _ <- IO(ctx.tick(5.hours))
          a1 <- cache.lookupNoUpdate(1)
          _ <- IO { assert(a1.contains("foo")) }
          _ <- cache.insert(1, "bar")
          _ <- IO(ctx.tick(7.hours + 1.second)) // expiration time reached for first timestamp
          a2 <- cache.lookupNoUpdate(1)
          _ <- IO { assert(a2.contains("bar")) }
          _ <- IO(ctx.tick(5.hours)) // expiration time reached for last timestamp
          a3 <- cache.lookupNoUpdate(1)
          _ <- IO { assert(a3.isEmpty) }
        } yield ()
      )
      spec.as(1).unsafeRunSync() must_== 1
    }

  }

}

object WithTestContext {

  def apply[A](f: TestContext => ContextShift[IO] => Temporal[IO] => A): A = {
    val ctx = TestContext()
    val cs: ContextShift[IO] = IO.contextShift(ctx)
    val timer: Temporal[IO] = ctx.timer[IO]
    f(ctx)(cs)(timer)
  }

}
