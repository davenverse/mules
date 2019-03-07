package io.chrisdavenport.mules

trait Cache[F[_], K, V]{
  def lookup(k: K): F[Option[V]]

  def insert(k: K, v: V): F[Unit]
  
  def delete(k: K): F[Unit]
}