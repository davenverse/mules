package io.chrisdavenport.mules.reload

import cats.effect.concurrent.{Ref, Semaphore}
import cats.effect._
import cats.implicits._
import io.chrisdavenport.mules._

import scala.collection.immutable.Map
import scala.concurrent.duration.Duration

class RefreshingCache[F[_]: Timer, K, V](
  innerCache: MemoryCache[F, K, V],
  refreshSemaphore: Semaphore[F],
  refreshTasks: Ref[F, Map[K, Fiber[F, Unit]]]
)(implicit F: Concurrent[F])
    extends CacheWithTimeout[F, K, V] {
  import RefreshingCache._

  /**
   * Manually Cancel All Current Reloads
   */
  def cancelRefreshes: F[Unit] =
    refreshTasks.modify { m =>
      (Map.empty, m.values.toList.traverse[F, Unit](_.cancel).void)
    }.flatten

  def delete(k: K): F[Unit] =
    refreshTasks.modify { m =>
      (m - k, m.get(k).traverse[F, Unit](_.cancel))
    }.flatten *>
    innerCache.delete(k)

  private def fetchAndInsert(k: K, fetch: F[V]): F[V] =
    for {
      v <- fetch
      _ <- insert(k, v)
    } yield v

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
   * This method always returns as is expected.
   */
  def lookupOrFetch(k: K, fetch: F[V]): F[V] =
    lookup(k).flatMap {
      case Some(v) => v.pure[F]
      case None => fetchAndInsert(k, fetch)
    }

  /**
   * This method always returns as is expected.
   */
  def lookupOrRefresh(k: K, refresh: F[V], period: TimeSpec): F[V] =
    setupRefresh(k, refresh, period) *> lookupOrFetch(k, refresh)

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
      } yield ()
    }.handleError(_ => ()) >> loop()

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

  private sealed trait RefreshStrategy

  private case class RefreshOnce(fetch: F[V]) extends RefreshStrategy

  private case class AutoRefresh(refresh: F[V], period: TimeSpec) extends RefreshStrategy




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
