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

package io.chrisdavenport.mules.caffeine

import scala.concurrent.duration._
import cats.effect._
import munit._
import io.chrisdavenport.mules.TimeSpec

class CaffeineCacheSpec extends CatsEffectSuite {
  test("CaffeineCache should get a value in a quicker period than the timeout") {
    for {
      cache <- CaffeineCache.build[IO, String, Int](
        Some(TimeSpec.unsafeFromDuration(1.second)),
        None,
        None
      )
      _ <- cache.insert("Foo", 1)
      _ <- Temporal[IO].sleep(1.milli)
      value <- cache.lookup("Foo")
    } yield assertEquals(value, Some(1))
  }

  test("CaffeineCache should remove a value after delete") {
    for {
      cache <- CaffeineCache.build[IO, String, Int](None, None, None)
      _ <- cache.insert("Foo", 1)
      _ <- cache.delete("Foo")
      value <- cache.lookup("Foo")
    } yield assertEquals(value, None)
  }

  test("CaffeineCache should Lookup after interval fails to get a value") {
    for {
      cache <- CaffeineCache.build[IO, String, Int](
        Some(TimeSpec.unsafeFromDuration(1.second)),
        None,
        None
      )
      _ <- cache.insert("Foo", 1)
      _ <- Temporal[IO].sleep(2.second)
      value <- cache.lookup("Foo")
    } yield assertEquals(value, None)
  }
}
