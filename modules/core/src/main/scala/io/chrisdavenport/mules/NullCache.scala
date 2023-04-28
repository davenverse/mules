package io.chrisdavenport.mules

import cats._
import cats.implicits._

private class NullCache[F[_], K, V](implicit F: Applicative[F]) extends Cache[F, K, V]{

  private val noneFV : F[Option[V]] = Option.empty[V].pure[F]

  // Members declared in io.chrisdavenport.mules.Delete
  def delete(k: K): F[Unit] = F.unit
  
  // Members declared in io.chrisdavenport.mules.Insert
  def insert(k: K, v: V): F[Unit] = F.unit
  
  // Members declared in io.chrisdavenport.mules.Lookup
  def lookup(k: K): F[Option[V]] = noneFV
}

object NullCache {
  def impl[F[_]: Applicative, K, V] : Cache[F, K, V] = new NullCache[F, K, V]
}