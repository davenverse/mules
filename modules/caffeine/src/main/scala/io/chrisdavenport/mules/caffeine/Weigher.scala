package io.chrisdavenport.mules.caffeine

import cats._
import com.github.benmanes.caffeine.{cache => ccache}

/** Calculates the ''weight'' of a Cache key and value.
  *
  * @note the units of ''weight'' are undefined. For example, a common and
  *       useful unit would be approximate byte usage, but that is not the
  *       only valid unit. Another valid unit could be volatility, e.g. values
  *       which tend to be more volatile in the cache could ''weigh'' more and
  *       thus have increased probability of eviction.
  *
  * @see [[https://www.javadoc.io/doc/com.github.ben-manes.caffeine/caffeine/latest/com/github/benmanes/caffeine/cache/Weigher.html]]
  */
trait Weigher[K, V] {

  def weighKey(key: K): Int
  def weighValue(value: V): Int
  def maximumWeight: Long

  final lazy val asJavaWeigher: ccache.Weigher[K, V] =
    Weigher.asJavaFromFunctions(weighKey, weighValue)

  final def weigh(key: K, value: V): Int =
    weighKey(key) + weighValue(value)

  final def contramapKey[A](f: A => K): Weigher[A, V] =
    Weigher.fromFunctions(
      maximumWeight
    )(
      weighKey _ compose f,
      weighValue
    )

  final def contramapValue[A](f: A => V): Weigher[K, A] =
    Weigher.fromFunctions(
      maximumWeight
    )(
      weighKey,
      weighValue _ compose f
    )

  final def bicontramap[A, B](f: A => K, g: B => V): Weigher[A, B] =
    Weigher.fromFunctions(maximumWeight)(weighKey _ compose f, weighValue _ compose g)

  final def applyToBuilder[F[_]](
    builder: ccache.Caffeine[K, V]
  )(implicit F: ApplicativeError[F, Throwable]): F[ccache.Caffeine[K, V]] =
    F.catchNonFatal(
      builder.weigher(asJavaWeigher).maximumWeight(maximumWeight)
    )

  final def withMaxWeight(maxWeight: Long): Weigher[K, V] =
    Weigher.fromFunctions(maxWeight)(weighKey _, weighValue _)
}

object Weigher {

  /** Convenience to directly create an instance of the underlying Java interface.
    */
  def asJavaFromFunctions[K, V](
    weighKey: K => Int,
    weighValue: V => Int
  ): ccache.Weigher[K, V] =
    new ccache.Weigher[K, V] {
      override def weigh(key: K, value: V): Int =
        weighKey(key) + weighValue(value)
    }

  /** Create a [[Weigher]] from two functions which calculate weight. */
  def fromFunctions[K, V](
    maxWeight: Long
  )(
    wk: K => Int,
    wv: V => Int
  ): Weigher[K, V] =
    new Weigher[K, V] {
      override final def weighKey(key: K): Int =
        wk(key)

      override final def weighValue(value: V): Int =
        wv(value)

      override final val maximumWeight: Long = maxWeight
    }

  /** Create a [[Weigher]] which always returns the same weight for a given key
    * or value.
    */
  def const[K, V](
    maxWeight: Long
  )(
    keyWeight: Int,
    valueWeight: Int
  ): Weigher[K, V] =
    fromFunctions(maxWeight)(Function.const(keyWeight), Function.const(valueWeight))

  /** Create a [[Weigher]] which calculates the approximate memory usage in
    * terms of bytes of keys and values.
    */
  def fromByteWeights[K, V](
    maxWeight: Long
  )(
    implicit K: ByteWeight[K], V: ByteWeight[V]
  ): Weigher[K, V] =
    fromFunctions(maxWeight)(
      K.weight _,
      V.weight _
    )
}
