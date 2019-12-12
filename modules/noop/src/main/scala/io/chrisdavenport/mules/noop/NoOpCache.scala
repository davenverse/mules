package io.chrisdavenport.mules.noop

import io.chrisdavenport.mules.{Cache, TimeSpec}
import cats.Applicative

private class NoOpCache[F[_], K, V](implicit F: Applicative[F]) extends Cache[F, K, V]{
  val noneF : F[Option[V]] = F.pure(None)
  // Members declared in io.chrisdavenport.mules.Delete
  def delete(k: K): F[Unit] = F.unit
  
  // Members declared in io.chrisdavenport.mules.Insert
  def insert(k: K, v: V): F[Unit] = F.unit
  def insertWithTimeout(optionTimeout: Option[TimeSpec])(k: K, v: V): F[Unit] = F.unit
  
  // Members declared in io.chrisdavenport.mules.Lookup
  def lookup(k: K): F[Option[V]] = noneF
}

object NoOpCache {
  def impl[F[_]: Applicative, K, V] : Cache[F, K, V] = new NoOpCache[F, K, V]
}