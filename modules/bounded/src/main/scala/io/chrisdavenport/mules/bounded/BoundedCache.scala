package io.chrisdavenport.mules.bounded

import cats.implicits._
import cats.effect._
import scala.collection.immutable.Queue
import cats.effect.concurrent._

sealed abstract class BoundedCache[F[_], K, V]{
  def lookup(k: K): F[Option[V]]
  def insert(k: K, v: V): F[Unit]
}

object BoundedCache {
  // Functional Tools For Interfacing with Queues
  implicit private class QueueOps[K](private val queue: Queue[K]){
    // Safe Uncons
    def unsnoc: Option[(K, Queue[K])] = 
      queue.headOption.map((_, queue.safeTail))
    def safeTail: Queue[K] = queue.headOption.fold(Queue.empty[K])(_ => queue.tail)
    def cons(k: K): Queue[K] = queue :+ k
  }


  // Max Size of Cache
  def create[F[_]: Sync, K, V](n: Int): F[BoundedCache[F, K, V]] = for {
    ref <- Ref.of[F, Map[K, V]](Map.empty[K, V])
    deq <- Ref.of(Queue.empty[K])
  } yield new BoundedCacheSync[F, K, V](ref, deq, n)

  private class BoundedCacheSync[F[_]: Sync, K, V](
    ref: Ref[F, Map[K, V]], 
    dequeue: Ref[F, Queue[K]],
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
      _ <- dequeue.update(_ :+ k)
    } yield ()
      
    private def removeLastElementInQueueIfFull: F[Unit] = 
      ref.get.map(_.size >= n).ifM(
        dequeue.modify(d => d.unsnoc.fold((d, Option.empty[K])){ case (a, d) => (d, a.some)}),
        Sync[F].pure(Option.empty[K])
      ) >>= {_.traverse_(k => ref.update(m => m - k))}
      
  }

  object Ops {

  }
}

