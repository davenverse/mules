package io.chrisdavenport.mules

trait Lookup[F[_], K, V]{
  def lookup(k: K): F[Option[V]]
}

trait Insert[F[_], K, V]{
  def insert(k: K, v: V): F[Unit]
}

trait InsertWithTimeout[F[_], K, V]{
  def insertWithTimeout(optionTimeout: Option[TimeSpec])(k: K, v: V): F[Unit]
}

trait Delete[F[_], K]{
  def delete(k: K): F[Unit]
}

trait Cache[F[_], K, V]
  extends Lookup[F, K, V]
  with Insert[F, K, V]
  with Delete[F, K]


trait CacheWithTimeout[F[_], K, V] extends Cache[F, K, V] with InsertWithTimeout[F, K, V]