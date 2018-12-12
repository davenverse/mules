package io.chrisdavenport.mules.bounded

import org.specs2.mutable.Specification
import cats.effect._

class BoundedCacheSpec extends Specification {

  "BoundedCache" should {
    "contain a single value correctly" in {
      val setup = for {
        cache <- BoundedCache.create[IO, String, Int](1)
        _ <- cache.insert("Foo", 1)
        value <- cache.lookup("Foo")
      } yield value
      setup.unsafeRunSync must_=== Some(1)
    }
    "remove values after the max size" in {
      val setup = for {
        cache <- BoundedCache.create[IO, String, Int](1)
        _ <- cache.insert("Foo", 1)
        value <- cache.lookup("Foo")
        _ <- cache.insert("Chicken", 5)
        none <- cache.lookup("Foo")
        last <- cache.lookup("Chicken")
      } yield (value, none, last)
      setup.unsafeRunSync must_=== ((Some(1), None, Some(5)))
    }


  }
}