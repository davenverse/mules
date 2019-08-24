
package io.chrisdavenport.mules.reload

import cats._
import cats.effect.concurrent.{Ref, Semaphore}
import cats.effect._
import cats.implicits._
import cats.collections.Dequeue
import io.chrisdavenport.mules._
import io.chrisdavenport.mules.reload.AutoFetchingCacheB.Refresh

import scala.collection.immutable.Map
import scala.concurrent.duration.Duration

class AutoFetchingCacheB[F[_] : Concurrent : Timer, K, V](
  private val innerCache: MemoryCache[F, K, V],
  private val refresh: Refresh[F, K]
) extends CacheWithTimeout[F, K, V] {
  import AutoFetchingCacheB._

  /**
   * Manually Cancel All Current Reloads
   */
  def cancelReloads: F[Unit] = refresh.cancelAll

  def delete(k: K): F[Unit] = innerCache.delete(k)

  private def fetchAndInsert(k: K, fetch: F[V]): F[V] =
    for {
      v <- fetch
      _ <- insert(k, v)
    } yield v

  /**
   * Insert an item in the cache, using the default expiration value of the cache.
   */
  def insert(k: K, v: V): F[Unit] =
    innerCache.insert(k, v)


  def insertWithTimeout(
                         optionTimeout: Option[TimeSpec]
                       )(k: K, v: V): F[Unit] =
    innerCache.insertWithTimeout(optionTimeout)(k, v)

  /**
   * Return all keys present in the cache, including expired items.
   **/
  def keys: F[List[K]] =
    innerCache.keys

  def lookup(k: K): F[Option[V]] =
    innerCache.lookup(k)

  def lookup(k: K, fs: FetchStrategy[F, V]): F[Option[V]] =
    lookupItemT(k, fs)

  /**
   * This method always returns as is expected.
   */
  def lookupOrFetch(k: K, fetch: F[V]): F[V] =
    lookup(k, FetchOnce(fetch)).map(_.get)

  /**
   * This method always returns as is expected.
   */
  def lookupOrAutoFetch(k: K, fetch: F[V], duration: TimeSpec): F[V] =
    lookup(k, AutoFetch(fetch, duration)).map(_.get)


  private def lookupItemT(k: K,
                          fs: FetchStrategy[F, V]): F[Option[V]] = {
    for {
      _ <- fs match {
             case AutoFetch(f, p) => setupRefresh(k, f, p)
             case _ => ().pure[F]
           }
      existing <- lookup(k)
      v <- (existing, fs) match {
        case (None, FetchOnce(fetch)) => fetchAndInsert(k, fetch).map(Option(_))
        case (None, AutoFetch(fetch, _)) => fetchAndInsert(k, fetch).map(Option(_))
        case (Some(v), _) => Some(v).pure[F]
        case _ => none[V].pure[F]
      }
    } yield v
  }

  private def setupRefresh(k: K, fetch: F[V], period: TimeSpec): F[Unit] = {

    def loop(): F[Unit] = {
      for {
        fiber <- Concurrent[F].start[V](
          Timer[F].sleep(Duration.fromNanos(period.nanos)) >> fetch
        )
        newValue <- fiber.join
        out <- insert(k, newValue)
      } yield out
      }.handleErrorWith(_ => loop()) >> loop()

    refresh match {
      case BoundedRefresh(s, tasks) =>
        def cancel(m: Map[K, (Int, Fiber[F, Unit])], popped: Option[K]): F[Map[K, (Int, Fiber[F, Unit])]] =
          (for {
            k <- popped
            (cpt, f) <- m.get(k)
          } yield
            if (cpt - 1 <= 0) f.cancel.as(m - k)
            else Applicative[F].pure(m)
            ).getOrElse(Applicative[F].pure(m))

        s.withPermit{
          for {
            (m, queue) <- tasks.get
            (newq, popped) = queue.push(k)
            m1 <- cancel(m, popped)
            m2 <- m1.get(k) match {
              case None => Concurrent[F].start(loop()).map(f => m1 + (k -> ((1, f))))
              case Some((cpt, f)) => Applicative[F].pure(m1 + (k -> ((cpt + 1, f))))
            }
            _ <- tasks.set((m2, newq))
          } yield ()
        }


      case UnboundedRefresh(s, tasks) =>
        s.withPermit{
          for {
            m <- tasks.get
            m1 <- m.get(k) match {
              case None => Concurrent[F].start(loop()).map(f => m + (k -> f))
              case Some(_) => Applicative[F].pure(m)
            }
            _ <- tasks.set(m1)
          } yield ()
        }
    }

  }

  /**
   * Return the size of the cache, including expired items.
   **/
  def size: F[Int] = innerCache.size

}

object AutoFetchingCacheB {

  sealed trait FetchStrategy [F[_], A] extends Serializable with Product {
    type Fetched
  }

  case class NoFetch[F[_], A]() extends FetchStrategy[F, A]{
     type Fetched = Option[A]
  }

  case class FetchOnce[F[_], A](fetch: F[A]) extends FetchStrategy[F, A]{
    type Fetched = A
  }


  case class AutoFetch[F[_], A](fetch: F[A], period: TimeSpec) extends FetchStrategy[F, A]{
    type Fetched = A
  }


  private sealed trait Refresh[F[_], K] {
    def cancelAll: F[Unit]
  }

  final private case class BoundedRefresh[F[_] : Monad, K](s: Semaphore[F],
                                                            tasks: Ref[F, (Map[K, (Int, Fiber[F, Unit])], BoundedQueue[K])]
                                                          ) extends Refresh[F, K] {
    def cancelAll: F[Unit] =
      tasks.modify { case (m, q) =>
        ((Map.empty, BoundedQueue.empty(q.maxSize)),
          m.values.toList.traverse[F, Unit] { case (_, f) => f.cancel }.void)
      }.flatten
  }

  final private case class UnboundedRefresh[F[_] : Monad, K]( s: Semaphore[F],
                                                              tasks: Ref[F, Map[K, Fiber[F, Unit]]]
                                                            ) extends Refresh[F, K] {
    def cancelAll: F[Unit] =
      tasks.modify { m =>
        (Map.empty,
          m.values.toList.traverse[F, Unit](_.cancel).void)
      }.flatten
  }


  final private case class BoundedQueue[A](maxSize: Int, currentSize: Int, queue: Dequeue[A]) {

    def push(a : A): (BoundedQueue[A], Option[A]) = {
      if((currentSize + 1) > maxSize)
        queue.unsnoc match {
          case None =>
            (BoundedQueue(maxSize, 1, queue.cons(a)), None)
          case Some((out, q)) => (BoundedQueue(maxSize, currentSize, q.cons(a)), Some(out))
        }
      else (BoundedQueue(maxSize, currentSize + 1, queue.cons(a)), None)
    }
  }

  private object BoundedQueue {
    def empty[A](maxSize : Int) : BoundedQueue[A] = BoundedQueue(maxSize, 0, Dequeue.empty)
  }

  /**
   * Create a new cache with a default expiration value for newly added cache items.
   *
   * Items that are added to the cache without an explicit expiration value (using insert) will be inserted with the default expiration value.
   *
   * If the specified default expiration value is None, items inserted by insert will never expire.
   **/
  def createCache[F[_] : Concurrent : Timer, K, V](
                                                    defaultExpiration: Option[TimeSpec],
                                                    maxParallelRefresh: Option[Int] = None
                                                  ): F[AutoFetchingCacheB[F, K, V]] =
    for {
      innerCache <- MemoryCache.createMemoryCache[F, K, V](defaultExpiration)
      s <- Semaphore(1)
      refresh <-
        maxParallelRefresh match {
          case Some(maxParallel) =>
            Ref.of[F, (Map[K, (Int, Fiber[F, Unit])], BoundedQueue[K])]((Map.empty, BoundedQueue.empty(maxParallel)))
              .map(ref =>
                BoundedRefresh(s, ref)
              )
          case None =>
            Ref.of[F, Map[K, Fiber[F, Unit]]](Map.empty)
              .map(ref =>
                UnboundedRefresh(s, ref)
              )
        }
    } yield new AutoFetchingCacheB[F, K, V](innerCache, refresh)

}
