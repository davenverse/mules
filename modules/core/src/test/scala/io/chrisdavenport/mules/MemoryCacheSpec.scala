/*
 * Copyright (c) 2018 Christopher Davenport
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package io.chrisdavenport.mules

import scala.concurrent.duration._
import cats.effect._
import munit._

class MemoryCacheSpec extends CatsEffectSuite {
  test("MemoryCache.ofSingleImmutableMap should get a value in a quicker period than the timeout") {
    for {
      cache <- MemoryCache.ofSingleImmutableMap[IO, String, Int](
        Some(TimeSpec.unsafeFromDuration(1.second))
      )(Async[IO])
      _ <- cache.insert("Foo", 1)
      _ <- IO.sleep(1.nano)
      value <- cache.lookup("Foo")
    } yield assertEquals(value, Some(1))
  }

  test("MemoryCache.ofSingleImmutableMap should remove a value after delete") {
    for {
      cache <- MemoryCache.ofSingleImmutableMap[IO, String, Int](None)(Async[IO])
      _ <- cache.insert("Foo", 1)
      _ <- cache.delete("Foo")
      value <- cache.lookup("Foo")
    } yield assertEquals(value, None)
  }

  test("MemoryCache.ofSingleImmutableMap should remove a value in mass delete") {
    for {
      cache <- MemoryCache.ofSingleImmutableMap[IO, String, Int](
        Some(TimeSpec.unsafeFromDuration(100.millis))
      )(Async[IO])
      _ <- cache.insert("Foo", 1)
      _ <- IO.sleep(200.millis)
      _ <- cache.purgeExpired
      value <- cache.lookupNoUpdate("Foo")
    } yield assertEquals(value, None)
  }

  test("MemoryCache.ofSingleImmutableMap should lookup after interval fails to get a value") {
    for {
      cache <- MemoryCache.ofSingleImmutableMap[IO, String, Int](
        Some(TimeSpec.unsafeFromDuration(100.millis))
      )(Async[IO])
      _ <- cache.insert("Foo", 1)
      _ <- IO.sleep(200.millis)
      value <- cache.lookup("Foo")
    } yield assertEquals(value, None)
  }

  test("MemoryCache.ofSingleImmutableMap should not Remove an item on lookup No Delete") {
    for {
      checkWasTouched <- Ref[IO].of(false)
      iCache <- MemoryCache.ofSingleImmutableMap[IO, String, Int](
        Some(TimeSpec.unsafeFromDuration(100.millis))
      )(Async[IO])
      cache = iCache.setOnDelete(_ => checkWasTouched.set(true))
      _ <- cache.insert("Foo", 1)
      _ <- IO.sleep(200.millis)
      value <- cache.lookupNoUpdate("Foo")
      wasTouched <- checkWasTouched.get
    } yield {
      assertEquals(value, Option.empty[Int])
      assert(!wasTouched)
    }
  }

  test("MemoryCache.ofShardedImmutableMap should get a value in a quicker period than the timeout") {
    for {
      cache <- MemoryCache.ofShardedImmutableMap[IO, String, Int](
        10,
        Some(TimeSpec.unsafeFromDuration(100.millis))
      )(Async[IO])
      _ <- cache.insert("Foo", 1)
      _ <- IO.sleep(1.nano)
      value <- cache.lookup("Foo")
    } yield assertEquals(value, Some(1))
  }

  test("MemoryCache.ofShardedImmutableMap should remove a value after delete") {
    for {
      cache <- MemoryCache.ofShardedImmutableMap[IO, String, Int](
        10,
        Some(TimeSpec.unsafeFromDuration(100.millis))
      )(Async[IO])
      _ <- cache.insert("Foo", 1)
      _ <- cache.delete("Foo")
      value <- cache.lookup("Foo")
    } yield assertEquals(value, None)
  }

  test("MemoryCache.ofShardedImmutableMap should remove a value in mass delete") {
    for {
      cache <- MemoryCache.ofShardedImmutableMap[IO, String, Int](
        10,
        Some(TimeSpec.unsafeFromDuration(100.millis))
      )(Async[IO])
      _ <- cache.insert("Foo", 1)
      _ <- IO.sleep(200.millis)
      _ <- cache.purgeExpired
      value <- cache.lookupNoUpdate("Foo")
    } yield assertEquals(value, None)
  }

  test("MemoryCache.ofShardedImmutableMap should lookup after interval fails to get a value") {
    for {
      cache <- MemoryCache.ofShardedImmutableMap[IO, String, Int](
        10,
        Some(TimeSpec.unsafeFromDuration(100.millis))
      )(Async[IO])
      _ <- cache.insert("Foo", 1)
      _ <- IO.sleep(200.millis)
      value <- cache.lookup("Foo")
    } yield assertEquals(value, None)
  }

  test("MemoryCache.ofShardedImmutableMap should not Remove an item on lookup No Delete") {
    for {
      checkWasTouched <- Ref[IO].of(false)
      iCache <- MemoryCache.ofShardedImmutableMap[IO, String, Int](
        10,
        Some(TimeSpec.unsafeFromDuration(100.millis))
      )(Async[IO])
      cache = iCache.setOnDelete(_ => checkWasTouched.set(true))
      _ <- cache.insert("Foo", 1)
      _ <- IO.sleep(200.millis)
      value <- cache.lookupNoUpdate("Foo")
      wasTouched <- checkWasTouched.get
    } yield {
      assertEquals(value, Option.empty)
      assert(!wasTouched)
    }
  }

  test("MemoryCache.ofConcurrentHashMap should get a value in a quicker period than the timeout") {
    for {
      cache <- MemoryCache.ofConcurrentHashMap[IO, String, Int](
        Some(TimeSpec.unsafeFromDuration(100.millis))
      )(Async[IO])
      _ <- cache.insert("Foo", 1)
      _ <- IO.sleep(1.nano)
      value <- cache.lookup("Foo")
    } yield assertEquals(value, Some(1))
  }

  test("MemoryCache.ofConcurrentHashMap should remove a value after delete") {
    for {
      cache <- MemoryCache.ofConcurrentHashMap[IO, String, Int](None)(Async[IO])
      _ <- cache.insert("Foo", 1)
      _ <- cache.delete("Foo")
      value <- cache.lookup("Foo")
    } yield assertEquals(value, None)
  }

  test("MemoryCache.ofConcurrentHashMap should Remove a value in mass delete") {
    for {
      cache <- MemoryCache.ofConcurrentHashMap[IO, String, Int](
        Some(TimeSpec.unsafeFromDuration(100.millis))
      )(Async[IO])
      _ <- cache.insert("Foo", 1)
      _ <- IO.sleep(200.millis)
      _ <- cache.purgeExpired
      value <- cache.lookupNoUpdate("Foo")
    } yield assertEquals(value, None)
  }

  test("MemoryCache.ofConcurrentHashMap should Lookup after interval fails to get a value") {
    for {
      cache <- MemoryCache.ofConcurrentHashMap[IO, String, Int](
        Some(TimeSpec.unsafeFromDuration(100.millis))
      )(Async[IO])
      _ <- cache.insert("Foo", 1)
      _ <- IO.sleep(200.millis)
      value <- cache.lookup("Foo")
    } yield assertEquals(value, None)
  }

  test("MemoryCache.ofConcurrentHashMap should Not Remove an item on lookup No Delete") {
    for {
      checkWasTouched <- Ref[IO].of(false)
      iCache <- MemoryCache.ofConcurrentHashMap[IO, String, Int](
        Some(TimeSpec.unsafeFromDuration(100.millis))
      )(Async[IO])
      cache = iCache.setOnDelete(_ => checkWasTouched.set(true))
      _ <- cache.insert("Foo", 1)
      _ <- IO.sleep(200.millis)
      value <- cache.lookupNoUpdate("Foo")
      wasTouched <- checkWasTouched.get
    } yield {
      assertEquals(value, Option.empty[Int])
      assert(!wasTouched)
    }
  }
}
