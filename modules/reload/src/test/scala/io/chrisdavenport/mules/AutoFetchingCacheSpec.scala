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
package reload

import cats.effect._

import munit._

import scala.concurrent.duration._

class AutoFetchingCacheSpec extends CatsEffectSuite {
  test("AutoFetchingCache should get a value in a quicker period than the timeout") {
    for {
      count <- Ref.of[IO, Int](0)
      cache <- AutoFetchingCache.createCache[IO, String, Int](
        Some(TimeSpec.unsafeFromDuration(1.second)),
        None
      )(_ => count.update(_ + 1).as(1))
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

      cache <- AutoFetchingCache.createCache[IO, String, Int](
        Some(TimeSpec.unsafeFromDuration(1.second)),
        None
      )(_ => count.update(_ + 1).as(1))
      _ <- cache.lookupCurrent("Foo")
      _ <- Temporal[IO].sleep(2.seconds)
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

      cache <- AutoFetchingCache.createCache[IO, String, Int](
        None,
        Some(AutoFetchingCache.RefreshConfig(TimeSpec.unsafeFromDuration(500.milliseconds)))
      )(_ => count.update(_ + 1).as(1))
      _ <- cache.lookupCurrent("Foo")
      _ <- Temporal[IO].sleep(2.seconds)
      value <- cache.lookupCurrent("Foo")
      cValue <- count.get

    } yield {
      assertEquals(value, 1)
      assert(cValue >= 4)
    }
  }

  test(
    "AutoFetchingCache should refetch value after autoReload timeout and before default expiration"
  ) {
    for {
      count <- Ref.of[IO, Int](0)

      cache <- AutoFetchingCache.createCache[IO, String, Int](
        TimeSpec.fromDuration(3.second),
        Some(AutoFetchingCache.RefreshConfig(TimeSpec.unsafeFromDuration(500.milliseconds)))
      )(_ => count.update(_ + 1) *> count.get)
      _ <- cache.lookupCurrent("Foo")
      _ <- Temporal[IO].sleep(2.seconds)
      value <- cache.lookupCurrent("Foo")
      cValue <- count.get

    } yield {
      assert(value >= 4)
      assert(cValue >= 4)
    }
  }
}
