package io.chrisdavneport.mules

import cats._
import cats.implicits._
import cats.effect._
import cats.effect.concurrent._

import scala.concurrent.duration._

trait BasicCache[F[_], K, V]{
  def get(k: K): F[V]
  def put(k: K, v: V): F[Unit]
  def clear: F[Unit]
}

object BasicCache {
  // Good For In Nanoseconds
  // Generator Function
  def uncancellable[F[_]: Async: Timer, K, V](goodFor: Long, gen: K => F[V]): F[BasicCache[F, K, V]] = for {
    genLock <- Semaphore.uncancelable[F](1)
    ref <- Ref.of[F, Map[K, (V, Long)]](Map())
  } yield {
    new BasicCache[F, K, V]{
      private def isStale(expiresAt: Long): F[Boolean] = for {
        now <- Timer[F].clockMonotonic(NANOSECONDS)
      } yield expiresAt >= now

      private def updateByGen(k: K): F[V] =
        genLock.withPermit(gen(k).flatMap(v => put(k, v).as(v)))

      private def updateIfNecessary(k: K, v: V, setAt: Long): F[V] = {
        def ifFalse: F[V] = Applicative[F].pure(v)
        isStale(setAt).ifM(updateByGen(k), ifFalse)
      }

      override def get(k: K): F[V] = for {
        cacheState <- ref.get
        v <- cacheState.get(k).fold(updateByGen(k)){ case (v, l) => updateIfNecessary(k, v, l)}
      } yield v

      override def put(k: K, v: V): F[Unit] = for {
        now <- Timer[F].clockMonotonic(NANOSECONDS)
        _ <- ref.update(_.updated(k , (v, now + goodFor)))
      } yield ()

      def clear: F[Unit] = ref.set(Map())
    }
    
  }


}