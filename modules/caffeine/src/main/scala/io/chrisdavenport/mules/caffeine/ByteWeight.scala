package io.chrisdavenport.mules.caffeine

import cats._
import cats.implicits._

/** A type class for measuring the ''approximate'' amount of memory a given
  * value uses.
  *
  * @note without hooking directly into JVM instrumentation, which is not
  *       something we want to do in general, for many (if not all) types the
  *       calculated memory usage of any given type will have some margin of
  *       error. When there is ambiguity, the provided instances error on over
  *       estimating the memory usage of a given value.
  */
trait ByteWeight[A] {

  /** Calculate the approximate memory usage, in terms of bytes, of a given
    * value. This value should never be negative. This value should be
    * non-negative.
    */
  def weight(value: A): Int

  /** Create a new [[ByteWeight]] by composing this one. */
  final def contramap[B](f: B => A): ByteWeight[B] =
    ByteWeight.from[B](weight _ compose f)
}

private[caffeine] trait LowPriorityByteWeightInstances1 {

  /** Create a [[ByteWeight]] value for `Foldable`, given some base cost and a
    * shallow cost calculation function.
    *
    * The shallow cost is the amount of bookkeeping memory that a given
    * `Foldable` must allocate relative to its size. This does ''not'' include
    * the value of the elements of `Foldable`. That calculation is handled by
    * the [[ByteWeight]] instance for the given `A`.
    */
  protected final def forFoldable[F[_], A](baseCost: Int, shallowCost: Int => Int)(implicit F: Foldable[F], A: ByteWeight[A]): ByteWeight[F[A]] =
    from{(fa: F[A]) =>
      val (deepCost: Int, size: Int) = fa.foldl((0, 0)){
        case ((deepCost, size), value) => (size + 1, deepCost + A.weight(value))
      }

      baseCost + shallowCost(size) + deepCost
    }

  /** A low priority [[ByteWeight]] instance for any `Foldable`. The base cost
    * and the shallow cost are very expensive because this instance doesn't
    * know anything about the underlying data structure.
    */
  final implicit def fallbackFoldableInstance[F[_]: Foldable, A: ByteWeight]: ByteWeight[F[A]] =
    forFoldable[F, A](512, (size: Int) => size * 128)

  /** Create a [[ByteWeight]] directly from a function. */
  final def from[A](f: A => Int): ByteWeight[A] =
    new ByteWeight[A] {
      override final def weight(value: A): Int = f(value)
    }

  /** Create a constant [[ByteWeight]] which returns the same weight for any
    * value of `A`. For example, any given boxed `Int` takes up 16
    * bytes.
    */
  final def const[A](weight: Int): ByteWeight[A] =
    from(Function.const(weight))
}

private[caffeine] trait LowPriorityByteWeightInstances0 extends LowPriorityByteWeightInstances1 {

  /** A [[ByteWeight]] instance for boxed arrays.
    */
  final implicit def boxedArrayInstance[A](implicit A: ByteWeight[A]): ByteWeight[Array[A]] =
    from{(fa: Array[A]) =>
      val (deepCost: Int, size: Int) = fa.foldLeft((0, 0)){
        case ((deepCost, size), value) => (size + 1, deepCost + A.weight(value))
      }

      16 + (size * 16) + deepCost
    }
}

object ByteWeight extends LowPriorityByteWeightInstances0 {

  def apply[A](implicit B: ByteWeight[A]): ByteWeight[A] = B

  // The "magic" numbers seen below are derived using org.openjdk.jol to
  // inspect the runtime representation of these values on JDK 14 with Scala
  // 2.13.3

  // These are going to be the measuring the boxed representation. //
  implicit lazy val booleanInstance: ByteWeight[Boolean] = const(16)
  implicit lazy val byteInstance: ByteWeight[Byte] = const(16)
  implicit lazy val charInstance: ByteWeight[Char] = const(16)
  implicit lazy val doubleInstance: ByteWeight[Double] = const(24)
  implicit lazy val floatInstance: ByteWeight[Float] = const(16)
  implicit lazy val intInstance: ByteWeight[Int] = const(16)
  implicit lazy val longInstance: ByteWeight[Long] = const(24)
  implicit lazy val shortInstance: ByteWeight[Short] = const(16)

  /** A [[ByteWeight]] instance for `String` values. */
  implicit lazy val stringInstance: ByteWeight[String] =
    from((s: String) =>
      40 + 8*((s.size - 1)/8 + 1)
    )

  implicit def listInstance[A: ByteWeight]: ByteWeight[List[A]] =
    forFoldable[List, A](16, _ * 24)

  implicit def optionInstance[A: ByteWeight]: ByteWeight[Option[A]] =
    forFoldable[Option, A](16, _ * 16)

  implicit def vectorInstance[A: ByteWeight]: ByteWeight[Vector[A]] = {
    val sizeF: Int => Int = {
      (size: Int) =>
      if (size < 32) {
        24 + (8 * ((size - 1)/2))
      } else {
        // After 32 elements objects taking up 144 bytes of heap are allocated
        // for every 32 elements.
        144 + (144 * ((size - 1)/32))
      }
    }
    forFoldable[Vector, A](56, sizeF)
  }
}
