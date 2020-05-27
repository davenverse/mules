package io.chrisdavenport.mules

import org.specs2.mutable.Specification
import scala.concurrent.duration._
import cats.effect._
// import cats.effect.concurrent._
// import cats.effect.implicits._
import cats.effect.IO
import cats.effect.laws.util.TestContext
import cats.effect.specs2.CatsIO

class LRUCacheSpec extends Specification with CatsIO {
  "LRUCache" should {
    "keep only requested number of elements" in {
      for {
        cache <- LRUCache.ofSingleImmutableMap[IO, Int, String](2, None)
        _ <- cache.insert(1, "foo")
        _ <- cache.insert(2, "bar")
        _ <- cache.insert(3, "baz")
        out1 <- cache.lookup(1)
        out2 <- cache.lookup(2)
        out3 <- cache.lookup(3)
      } yield {
        (out1, out2, out3).must_===((None, Some("bar"), Some("baz")))
      }
    }

    "override with newer lookups" in {
      for {
        cache <- LRUCache.ofSingleImmutableMap[IO, Int, String](2, None)
        _ <- cache.insert(1, "foo")
        _ <- cache.insert(2, "bar")
        _ <- cache.lookup(1)
        _ <- cache.insert(3, "baz")
        out1 <- cache.lookup(1)
        out2 <- cache.lookup(2)
        out3 <- cache.lookup(3)
      } yield {
        (out1, out2, out3).must_===((Some("foo"), None, Some("baz")))
      }
    }

    "remove a value after delete" in {
      val ctx = TestContext()
      implicit val testTimer: Timer[IO] = ctx.timer[IO]
      val setup = for {
        cache <- LRUCache.ofSingleImmutableMap[IO, String, Int](5, None)(Concurrent[IO], testTimer.clock)
        _ <- cache.insert("Foo", 1)
        _ <- cache.delete("Foo")
        value <- cache.lookup("Foo")
      } yield value
      setup.map(_ must_=== None)
    }

    "Remove a value in mass delete" in {
      val ctx = TestContext()
      implicit val testTimer: Timer[IO] = ctx.timer[IO]
      val setup = for {
        cache <- LRUCache.ofSingleImmutableMap[IO, String, Int](5, Some(TimeSpec.unsafeFromDuration(1.second)))(Concurrent[IO], testTimer.clock)
        _ <- cache.insert("Foo", 1)
        _ <- Sync[IO].delay(ctx.tick(2.seconds))
        _ <- cache.purgeExpired
        value <- cache.lookup("Foo")
      } yield value
      setup.map(_ must_=== None)
    }

    "Lookup after interval fails to get a value" in {
      val ctx = TestContext()
      implicit val testTimer: Timer[IO] = ctx.timer[IO]
      val setup = for {
        cache <- LRUCache.ofSingleImmutableMap[IO, String, Int](5, Some(TimeSpec.unsafeFromDuration(1.second)))(Concurrent[IO], testTimer.clock)
        _ <- cache.insert("Foo", 1)
        _ <- Sync[IO].delay(ctx.tick(2.seconds))
        value <- cache.lookup("Foo")
      } yield value
      setup.map(_ must_=== None)
    }
  }


}










