package io.chrisdavenport.mules.reload

import cats._
import cats.effect._
import cats.effect.std.Semaphore
import cats.syntax.all._
import cats.collections.Dequeue
import io.chrisdavenport.mules._
import io.chrisdavenport.mules.reload.AutoFetchingCache.Refresh

import scala.collection.immutable.Map
import scala.concurrent.duration.Duration

class AutoFetchingCache[F[_]: Temporal, K, V](
  private val values: Ref[F, Map[K, AutoFetchingCache.CacheContent[F, V]]],
  val defaultExpiration: Option[TimeSpec],
  private val refresh: Option[Refresh[F, K]],
  val fetch: K => F[V]
) extends Lookup[F, K, V] {
  import AutoFetchingCache._

  /**
   * Manually Cancel All Current Reloads
   */
  def cancelReloads: F[Unit] =
    refresh.fold(Applicative[F].unit)(_.cancelAll)

  private def extractContentT(k: K, t: TimeSpec)(content: CacheContent[F, V]): F[V] = content match {
    case v0: Fetching[F, V] => v0.f.join.flatMap(succeedOrThrow(_))
    case v0: CacheItem[F, V] =>
      if (isExpired(t, v0)) fetchAndInsert(k)
      else Applicative[F].pure(v0.item)
  }

  private def fetchAndInsert(k: K): F[V] =
    for {
      v <- fetch(k)
      _ <- insert(k, v)
    } yield v

  /**
   * Insert an item in the cache, using the default expiration value of the cache.
   */
  private def insert(k: K, v: V): F[Unit] =
    insertWithTimeout(defaultExpiration)(k, v)

  private def insertFetching(k: K)(f: Fiber[F, Throwable, V]): F[Unit] = {
    values.update(_ + (k -> Fetching[F, V](f)))
  }

  /**
   * Insert an item in the cache, with an explicit expiration value.
   *
   * If the expiration value is None, the item will never expire. The default expiration value of the cache is ignored.
   *
   * The expiration value is relative to the current clockMonotonic time, i.e. it will be automatically added to the result of clockMonotonic for the supplied unit.
   **/
  def insertWithTimeout(
    optionTimeout: Option[TimeSpec]
  )(k: K, v: V): F[Unit] = {
    for {
      now <- Clock[F].monotonic
      timeout = optionTimeout.map(ts => TimeSpec.unsafeFromNanos(now.toNanos + ts.nanos))
      _ <- values.update(_ + (k -> CacheItem[F, V](v, timeout)))
    } yield ()
  }

  private def isExpired(checkAgainst: TimeSpec, cacheItem: CacheItem[F, V]): Boolean = {
    cacheItem.itemExpiration.fold(false) {
      case e if e.nanos < checkAgainst.nanos => true
      case _ => false
    }
  }

  /**
   * Return all keys present in the cache, including expired items.
   **/
  def keys: F[List[K]] =
    values.get.map(_.keys.toList)

  /**
   * Looks Up a Value in the Cache, since this
   * cache is always populated it will always be
   * Some.
   */
  def lookup(k: K): F[Option[V]] =
    lookupCurrent(k).map(_.some)

  /**
   * This method always returns as is expected.
   */
  def lookupCurrent(k: K): F[V] =
    Clock[F].monotonic
      .flatMap(now => lookupItemT(k, TimeSpec.unsafeFromNanos(now.toNanos)))

  private def lookupItemSimple(k: K): F[Option[CacheContent[F, V]]] =
    values.get.map(_.get(k))


  private def lookupItemT(k: K,t: TimeSpec): F[V] = {
    for {
      _ <- setupRefresh(k)
      i <- lookupItemSimple(k)
      v <- i match {
        case None => fetchAndInsert(k)
        case Some(content) => extractContentT(k, t)(content)
      }
    } yield v
  }

  private def setupRefresh(k: K): F[Unit] = {
    refresh.map { r =>
      def loop(): F[Unit] = {
        for {
          fiber <- Spawn[F].start[V](
            Temporal[F].sleep(Duration.fromNanos(r.period.nanos)) >> fetch(k)
          )
          _ <- insertFetching(k)(fiber)
          outcome <- fiber.join
          newValue <- succeedOrThrow(outcome)
          out <- insert(k, newValue)
        } yield out
      }.handleErrorWith(_ => loop()) >> loop()

      r match {
        case BoundedRefresh(_, s, tasks) =>
          def cancel(m: Map[K, (Int, Fiber[F, Throwable, Unit])], popped: Option[K]): F[Map[K, (Int, Fiber[F, Throwable, Unit])]] =
            (for {
              k <- popped
              (cpt, f) <- m.get(k)
            } yield
              if (cpt - 1 <= 0) f.cancel.as(m - k)
              else Applicative[F].pure(m)
            ).getOrElse(Applicative[F].pure(m))

            s.permit.use { _ =>
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


          case UnboundedRefresh(_, s, tasks) =>
            s.permit.use { _ =>
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
      }.getOrElse(Applicative[F].unit)
    }

  /**
   * Return the size of the cache, including expired items.
   **/
  def size: F[Int] =
    values.get.map(_.size)

}

object AutoFetchingCache {
  final object FetchCancelled extends RuntimeException

  private def succeedOrThrow[F[_]: ApplicativeThrow, A](
    outcome: Outcome[F, Throwable, A]
  ): F[A] =
    outcome match {
      case Outcome.Succeeded(x) => x
      case Outcome.Canceled() => FetchCancelled.raiseError[F, A]
      case Outcome.Errored(e) => e.raiseError[F, A]
    }

  private sealed trait Refresh[F[_], K] {
    def period: TimeSpec

    def cancelAll: F[Unit]
  }

  final private case class BoundedRefresh[F[_] : Monad, K](
    period: TimeSpec,
    s: Semaphore[F],
    tasks: Ref[F, (Map[K, (Int, Fiber[F, Throwable, Unit])], BoundedQueue[K])]
  ) extends Refresh[F, K] {
    def cancelAll: F[Unit] =
      tasks.modify { case (m, q) =>
        ((Map.empty, BoundedQueue.empty(q.maxSize)),
          m.values.toList.traverse[F, Unit] { case (_, f) => f.cancel }.void)
      }.flatten
  }

  final private case class UnboundedRefresh[F[_] : Monad, K](
    period: TimeSpec,
    s: Semaphore[F],
    tasks: Ref[F, Map[K, Fiber[F, Throwable, Unit]]]
  ) extends Refresh[F, K] {
    def cancelAll: F[Unit] =
      tasks.modify { m =>
        (Map.empty,
          m.values.toList.traverse[F, Unit](_.cancel).void)
      }.flatten
  }


  /**
   * Cache Content - What is present in the cache at any
   * moment.
   *
   * Fetching - The fiber of the currently running computation
   * to create get the value for the cache
   *
   * CacheItem - A value in the cache.
   */
  private sealed abstract class CacheContent[F[_], A]

  private case class Fetching[F[_], A](f: Fiber[F, Throwable, A]) extends CacheContent[F, A]
  private case class CacheItem[F[_], A](
    item: A,
    itemExpiration: Option[TimeSpec]
  ) extends CacheContent[F, A]

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
  def createCache[F[_]: Temporal, K, V](
    defaultExpiration: Option[TimeSpec],
    refreshConfig: Option[RefreshConfig]
  )(fetch: K => F[V]): F[AutoFetchingCache[F, K, V]] =
    for {
      valuesRef <- Ref.of[F, Map[K, CacheContent[F, V]]](Map.empty)
      s <- Semaphore(1)
      refresh <- refreshConfig.traverse[F, Refresh[F, K]] { conf =>
        conf.maxParallelRefresh match {
        case Some(maxParallel) =>
          Ref.of[F, (Map[K, (Int, Fiber[F, Throwable, Unit])], BoundedQueue[K])]((Map.empty, BoundedQueue.empty(maxParallel)))
            .map(ref =>
              BoundedRefresh(conf.period, s, ref)
            )
        case None =>
          Ref.of[F, Map[K, Fiber[F, Throwable, Unit]]](Map.empty)
            .map(ref =>
              UnboundedRefresh(conf.period, s, ref)
            )
      }}
    } yield new AutoFetchingCache[F, K, V](valuesRef, defaultExpiration, refresh, fetch)


  final class RefreshConfig private (
    val period: TimeSpec,
    val maxParallelRefresh: Option[Int]
  )
  object RefreshConfig {
    def apply(period: TimeSpec, maxParallelRefresh: Option[Int] = None): RefreshConfig =
      new RefreshConfig(period, maxParallelRefresh)
  }

}
