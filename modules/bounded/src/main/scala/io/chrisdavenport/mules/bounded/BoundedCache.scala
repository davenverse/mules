package io.chrisdavenport.mules.bounded

import cats.collections.Dequeue
import cats.implicits._
import cats.effect._
import cats.effect.concurrent._

sealed abstract class BoundedCache[F[_], K, V]{
  def lookup(k: K): F[Option[V]]
  def insert(k: K, v: V): F[Unit]
}

object BoundedCache {
  // Max Size of Cache
  def create[F[_]: Sync, K, V](n: Int): F[BoundedCache[F, K, V]] = for {
    ref <- Ref.of[F, Map[K, V]](Map.empty[K, V])
    deq <- Ref.of(Dequeue.empty[K])
  } yield new BoundedCacheSync[F, K, V](ref, deq, n)

  private class BoundedCacheSync[F[_]: Sync, K, V](
    ref: Ref[F, Map[K, V]], 
    dequeue: Ref[F, Dequeue[K]],
    n: Int
  ) extends BoundedCache[F, K, V]{
    def lookup(k: K): F[Option[V]] = 
    
    ref.get.map(_.get(k))
    def insert(k: K, v: V): F[Unit] = for {
      _ <- removeLastElementInQueueIfFull
      out <- addElementToQueue(k, v)
    } yield out

    private def addElementToQueue(
      k: K,
      v: V
    ): F[Unit] = for {
      _ <- ref.update(m => m + ((k, v)))
      _ <- dequeue.update(_.cons(k))
    } yield ()
      
    private def removeLastElementInQueueIfFull: F[Unit] = 
      ref.get.map(_.size >= n).ifM(
        dequeue.modify(d => d.unsnoc.fold((d, Option.empty[K])){ case (a, d) => (d, a.some)}),
        Sync[F].pure(Option.empty[K])
      ) >>= {_.traverse_(k => ref.update(m => m - k))}
      
  }
}

