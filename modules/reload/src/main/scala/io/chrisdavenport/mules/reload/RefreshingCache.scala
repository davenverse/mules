package io.chrisdavenport.mules.reload

import cats.effect.concurrent.{Ref, Semaphore}
import cats.effect._
import cats.implicits._
import io.chrisdavenport.mules._

import scala.collection.immutable.Map
import scala.concurrent.duration.Duration

/**
 * A local memory cache that allows auto refreshing given a F[V]
 * This class provides the same set of features as [[CacheWithTimeout]]
 * plus 2 more methods
 * 1. [[lookupOrFetch]]
 * 2. [[lookupOrRefresh]]
 */
class RefreshingCache[F[_]: Timer, K, V] private (
  innerCache: MemoryCache[F, K, V],
  refreshSemaphore: Semaphore[F],
  refreshTasks: Ref[F, Map[K, Fiber[F, Unit]]]
)(implicit F: Concurrent[F])
    extends CacheWithTimeout[F, K, V] {
  import RefreshingCache._

  /**
   * Manually Cancel All Current Reloads
   */
  def cancelRefreshes: F[Int] =
    refreshTasks.modify { m =>
      (Map.empty, m.values.toList.traverse[F, Unit](_.cancel).map(_.size))
    }.flatten

  def delete(k: K): F[Unit] =
    deregisterRefresh(k) *> innerCache.delete(k)

  /**
   * Return the size of the cache, including expired items.
   **/
  def size: F[Int] = innerCache.size

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

  /**
   * Try look up k. If the key is not present, will use fetch and insert using fetch
   * This method always returns as is expected.
   */
  def lookupOrFetch(k: K, fetch: F[V]): F[V] =
    lookup(k).flatMap {
      case Some(v) => v.pure[F]
      case None => fetchAndInsert(k, fetch)
    }

  /**
   * Try look up k, if the key is not present it will setup an auto {@code refresh} every {@code period}
   * This method is idempotent and always returns as expected.
   * Note, by design, it does not override an existing refresh effect for the same k.
   * To setup a new refresh method for it, you must
   * first {@code delete(k)} and then call this method.
   */
  def lookupOrRefresh(k: K, refresh: F[V], period: TimeSpec): F[V] =
    lookup(k).flatMap {
      case Some(v) => v.pure[F]
      case None => setupRefresh(k, refresh, period) *> fetchAndInsert(k, refresh)
    }

  private def fetchIfKeyExist(k: K, fetch: F[V]): F[Option[V]] =
    lookup(k).flatMap(_.traverse(_ => fetch))

  private def setupRefresh(k: K, fetch: F[V], period: TimeSpec): F[Unit] = {
    def loop(): F[Unit] = {
      for {
        fiber <- Concurrent[F].start(
          Timer[F].sleep(Duration.fromNanos(period.nanos)) >> fetchIfKeyExist(k, fetch)
        )
        newValueO <- fiber.join
        _ <- newValueO.traverse(nv => insert(k, nv))
      } yield newValueO
    }.attempt.flatMap {
      case Left(_) => loop() //tolerate All errors
      case Right(None) => deregisterRefresh(k).void //key already removed, deregister
      case Right(Some(_)) => loop()
    }

    if (innerCache.defaultExpiration.fold(false)(_.nanos < period.nanos))
      F.raiseError(RefreshDurationTooLong(period, innerCache.defaultExpiration.get))
    else {
      refreshSemaphore.withPermit {
        for {
          m <- refreshTasks.get
          m1 <- m.get(k) match {
            case None => Concurrent[F].start(loop()).map(f => m + (k -> f))
            case Some(_) => F.pure(m)
          }
          _ <- refreshTasks.set(m1)
        } yield ()
      }
    }
  }

  private def deregisterRefresh(k: K): F[Option[Unit]] =
    refreshTasks.modify { m =>
      (m - k, m.get(k).traverse[F, Unit](_.cancel))
    }.flatten


  private def fetchAndInsert(k: K, fetch: F[V]): F[V] =
    for {
      v <- fetch
      _ <- insert(k, v)
    } yield v

}

object RefreshingCache {


  def createCache[F[_]: Concurrent: Timer, K, V](
      defaultExpiration: Option[TimeSpec]
  ): F[RefreshingCache[F, K, V]] =
    for {
      innerCache <- MemoryCache.createMemoryCache[F, K, V](defaultExpiration)
      s <- Semaphore(1)
      tasks <- Ref.of[F, Map[K, Fiber[F, Unit]]](Map.empty)
    } yield new RefreshingCache[F, K, V](innerCache, s, tasks)

  case class RefreshDurationTooLong(duration: TimeSpec, expiration: TimeSpec)
      extends RuntimeException {
    override def getMessage: String =
      s"refreshing period ($duration) cannot be longer than expiration (${expiration})"
  }
}
