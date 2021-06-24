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

  implicit val instances: Order[TimeSpec] = new Order[TimeSpec] with Show[TimeSpec]{
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

}
