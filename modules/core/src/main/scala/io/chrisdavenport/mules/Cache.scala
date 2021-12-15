package io.chrisdavenport.mules

import cats._
import cats.syntax.all._

trait Lookup[F[_], K, V]{
  def lookup(k: K): F[Option[V]]
}

object Lookup {

  def mapValues[F[_]: Functor, K, A, B](l: Lookup[F, K, A])(f: A => B): Lookup[F, K, B] = 
    new Lookup[F, K, B]{
      def lookup(k: K): F[Option[B]] = l.lookup(k).map(_.map(f))
    }

  def contramapKeys[F[_], K, B, A](l: Lookup[F, K, A])(f: B => K): Lookup[F, B, A] = 
    new Lookup[F, B, A]{
      def lookup(k: B): F[Option[A]] = l.lookup(f(k))
    }

  def evalMap[F[_]: Monad, K, A, B](l: Lookup[F, K, A])(f: A => F[B]): Lookup[F, K, B] = 
    new Lookup[F, K, B]{
      def lookup(k: K): F[Option[B]] = l.lookup(k).flatMap(_.traverse(f))
    }

  def mapK[F[_], G[_], K, V](l: Lookup[F, K, V])(fk: F ~> G): Lookup[G, K, V] =
    new Lookup[G, K, V]{
      def lookup(k: K): G[Option[V]] = fk(l.lookup(k))
    }
}

trait Get[F[_], K, V]{
  def get(k: K): F[V]
}

object Get {

  def mapValues[F[_]: Functor, K, A, B](l: Get[F, K, A])(f: A => B): Get[F, K, B] = 
    new Get[F, K, B]{
      def get(k: K): F[B] = l.get(k).map(f)
    }

  def contramapKeys[F[_], K, B, A](l: Get[F, K, A])(g: B => K): Get[F, B, A] = 
    new Get[F, B, A]{
      def get(k: B): F[A] = l.get(g(k))
    }

  def evalMap[F[_]: Monad, K, A, B](l: Get[F, K, A])(f: A => F[B]): Get[F, K, B] = 
    new Get[F, K, B]{
      def get(k: K): F[B] = l.get(k).flatMap(f)
    }

  def mapK[F[_], G[_], K, V](g: Get[F, K, V])(fk: F ~> G): Get[G, K, V] = 
    new Get[G, K, V]{
      def get(k: K): G[V] = fk(g.get(k))
    }

}

trait Insert[F[_], K, V]{
  def insert(k: K, v: V): F[Unit]
}

object Insert {

  def contramapValues[F[_]: Functor, K, A, B](i: Insert[F, K, A])(g: B => A): Insert[F, K, B] =
    new Insert[F, K, B]{
      def insert(k: K, v: B): F[Unit] = i.insert(k, g(v))
    }

  def contramapKeys[F[_]: Functor, A, B, V](i: Insert[F, A, V])(g: B => A): Insert[F, B, V] = 
    new Insert[F, B, V]{
      def insert(k: B, v: V): F[Unit] = i.insert(g(k), v)
    }

  def mapK[F[_], G[_], K, V](i: Insert[F, K, V])(fk: F ~> G): Insert[G, K, V] =
    new Insert[G, K, V]{
      def insert(k: K, v: V): G[Unit] = fk(i.insert(k, v))
    }

}

trait Delete[F[_], K]{
  def delete(k: K): F[Unit]
}

object Delete {
  def contramap[F[_], A, B](d: Delete[F, A])(g: B => A): Delete[F, B] =
    new Delete[F, B]{
      def delete(k: B) = d.delete(g(k))
    }

  def mapK[F[_], G[_], K](d: Delete[F, K])(fk: F ~> G): Delete[G, K] = 
    new Delete[G, K]{
      def delete(k: K): G[Unit] = fk(d.delete(k))
    }
}

trait Cache[F[_], K, V] 
  extends Lookup[F, K, V]
  with Insert[F, K, V]
  with Delete[F, K]

object Cache {
  def imapValues[F[_]: Functor, K, A, B](cache: Cache[F, K, A])(f: A => B, g: B => A): Cache[F, K, B] = 
    new Cache[F, K, B]{
      def lookup(k: K): F[Option[B]] = cache.lookup(k).map(_.map(f))
      def insert(k: K, v: B): F[Unit] = cache.insert(k, g(v))
      def delete(k: K): F[Unit] = cache.delete(k)
    }

  def contramapKeys[F[_], K1, K2, V](c: Cache[F, K1, V])(g: K2 => K1): Cache[F, K2, V] =
    new Cache[F, K2, V] {
      def lookup(k: K2): F[Option[V]] = c.lookup(g(k))
      def insert(k: K2, v: V): F[Unit] = c.insert(g(k), v)
      def delete(k: K2): F[Unit] = c.delete(g(k))
    }

  def imapEval[F[_]: Monad, K, V1, V2](
    c: Cache[F, K, V1]
  )(f: V1 => F[V2], g: V2 => V1): Cache[F, K, V2] =
    new Cache[F, K, V2] {
      def lookup(k: K): F[Option[V2]] = c.lookup(k).flatMap(_.traverse(f))
      def insert(k: K, v: V2): F[Unit] = c.insert(k, g(v))
      def delete(k: K): F[Unit] = c.delete(k)
    }

  def mapK[F[_], G[_], K, V](cache: Cache[F, K, V])(fk: F ~> G): Cache[G, K, V] =
    new Cache[G, K, V]{
      def lookup(k: K): G[Option[V]] = fk(cache.lookup(k))
      def insert(k: K, v: V): G[Unit] = fk(cache.insert(k, v))
      def delete(k: K): G[Unit] = fk(cache.delete(k))
    }
}