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
import java.util.concurrent.TimeUnit
import cats._
import cats.implicits._

// Value of Time In Nanoseconds
final class TimeSpec private (
    val nanos: Long
) extends AnyVal {
  override def toString(): String = s"TimeSpec($nanos nanos)"
}
object TimeSpec {

  implicit val instances: Order[TimeSpec] with Show[TimeSpec] = new Order[TimeSpec]
    with Show[TimeSpec] {
    override def compare(x: TimeSpec, y: TimeSpec): Int =
      Order[Long].compare(x.nanos, y.nanos)
    override def show(t: TimeSpec): String = show"TimeSpec(${t.nanos})"
  }

  def fromDuration(duration: FiniteDuration): Option[TimeSpec] =
    Alternative[Option].guard(duration > 0.nanos).as(unsafeFromDuration(duration))

  def unsafeFromDuration(duration: FiniteDuration): TimeSpec =
    new TimeSpec(duration.toNanos)

  def fromNanos(l: Long): Option[TimeSpec] =
    Alternative[Option].guard(l > 0).as(unsafeFromNanos(l))

  def unsafeFromNanos(l: Long): TimeSpec =
    new TimeSpec(l)

  def toDuration(timeSpec: TimeSpec): FiniteDuration =
    Duration(timeSpec.nanos, TimeUnit.NANOSECONDS)

  /**
   * The absolute expiration for an entry inserted at `nowNanos` with a lifetime of `ttl`,
   * saturating rather than wrapping.
   *
   * Long nanosecond arithmetic wraps silently on overflow, and expiry is tested with
   * `expiration < now`, so a wrapped -- and therefore negative -- expiration reads as "already
   * expired". Without this, a sufficiently large TTL makes an entry expire immediately, which is
   * the exact opposite of what was asked for.
   */
  private[mules] def expiresAt(nowNanos: Long, ttl: TimeSpec): TimeSpec = {
    val sum = nowNanos + ttl.nanos
    // Overflow happened iff the operands agree in sign and the result differs.
    val overflowed = ((nowNanos ^ sum) & (ttl.nanos ^ sum)) < 0
    if (!overflowed) new TimeSpec(sum)
    else if (ttl.nanos > 0) new TimeSpec(Long.MaxValue)
    else new TimeSpec(Long.MinValue)
  }

}
