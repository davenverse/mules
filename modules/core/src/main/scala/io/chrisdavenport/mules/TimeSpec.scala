package io.chrisdavenport.mules

import cats.Alternative
import cats.syntax.functor._
import cats.instances.option._
import scala.concurrent.duration._
// Value of Time In Nanoseconds
final class TimeSpec private (
                         val nanos: Long
                       ) extends AnyVal
object TimeSpec {

  def fromDuration(duration: FiniteDuration): Option[TimeSpec] =
    Alternative[Option].guard(duration > 0.nanos).as(unsafeFromDuration(duration))

  def unsafeFromDuration(duration: FiniteDuration): TimeSpec =
    new TimeSpec(duration.toNanos)

  def fromNanos(l: Long): Option[TimeSpec] =
    Alternative[Option].guard(l > 0).as(unsafeFromNanos(l))

  def unsafeFromNanos(l: Long): TimeSpec =
    new TimeSpec(l)

}
