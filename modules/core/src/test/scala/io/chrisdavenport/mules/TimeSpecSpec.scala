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

class TimeSpecSpec extends CatsEffectSuite {

  test("expiresAt adds normally when there is no overflow") {
    val now = 1000L
    val ttl = TimeSpec.unsafeFromNanos(500L)
    assertEquals(TimeSpec.expiresAt(now, ttl).nanos, 1500L)
  }

  test("expiresAt saturates instead of wrapping on overflow") {
    val now = Long.MaxValue - 10L
    val ttl = TimeSpec.unsafeFromNanos(1000L)
    val expiry = TimeSpec.expiresAt(now, ttl).nanos
    assertEquals(expiry, Long.MaxValue)
    assert(expiry > 0, "a wrapped expiry would be negative and read as expired")
  }

  test("a saturated expiry is in the future, not the past") {
    // This is the behaviour the overflow actually broke: expiry is tested with
    // `expiration < now`, so a wrapped negative value made the entry expire
    // immediately rather than lasting essentially forever.
    val now = Long.MaxValue - 10L
    val ttl = TimeSpec.unsafeFromNanos(Long.MaxValue)
    assert(TimeSpec.expiresAt(now, ttl).nanos >= now)
  }

  test("expiresAt handles the largest possible ttl from a duration") {
    val now = 1L
    val ttl = TimeSpec.unsafeFromNanos(Long.MaxValue)
    assertEquals(TimeSpec.expiresAt(now, ttl).nanos, Long.MaxValue)
  }

  test("a huge ttl does not make an entry immediately expired") {
    val hugeTtl = TimeSpec.unsafeFromNanos(Long.MaxValue)
    for {
      cache <- MemoryCache.ofSingleImmutableMap[IO, String, Int](None)
      _ <- cache.insertWithTimeout(Some(hugeTtl))("k", 1)
      out <- cache.lookup("k")
    } yield assertEquals(out, Some(1))
  }

  test("a normal ttl still expires when it should") {
    for {
      cache <- MemoryCache.ofSingleImmutableMap[IO, String, Int](None)
      _ <- cache.insertWithTimeout(TimeSpec.fromDuration(1.nanosecond))("k", 1)
      _ <- IO.sleep(10.milliseconds)
      out <- cache.lookup("k")
    } yield assertEquals(out, None)
  }
}
