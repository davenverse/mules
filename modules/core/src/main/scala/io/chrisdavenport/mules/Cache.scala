package io.chrisdavenport.mules

trait Lookup[F[_], K, V]{
  def lookup(k: K): F[Option[V]]
}

trait Get[F[_], K, V]{
  def get(k: K): F[V]
}

trait Insert[F[_], K, V]{
  def insert(k: K, v: V): F[Unit]
}

trait Delete[F[_], K]{
  def delete(k: K): F[Unit]
}

trait Cache[F[_], K, V] 
  extends Lookup[F, K, V]
  with Insert[F, K, V]
  with Delete[F, K]