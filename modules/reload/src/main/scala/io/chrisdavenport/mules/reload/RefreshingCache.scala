package io.chrisdavenport.mules.reload

import cats.effect.concurrent.{Ref, Semaphore}
import cats.effect._
import cats.implicits._
import io.chrisdavenport.mules._

import scala.collection.immutable.Map
import scala.concurrent.duration.Duration

/**
 * A local memory cache that allows auto refreshing given a F[V]
 * This class provides the same set of features as [[MemoryCache]]
 * plus 2 more methods
 * 1. [[lookupOrFetch]]
 * 2. [[lookupOrRefresh]]
 * The [[lookupOrRefresh]] method allows users to have an item
 * periodically refreshed from the backend. As long as the
 * refresh successes, user will
 * 1. never have a cache miss and,
 * 2. never experience the data retrieve latency except the first read.
 * Also [[lookupOrRefresh]] allow users to use custom logic to catch
 * errors during refresh. This, combined with a relatively long expiration
 * period and a short refresh period enable, the cache to tolerant short
 * backend service disruption while keeping the value updated (when the service
 * is up).
 *
 * Major caveat:
 * There is no automatic mechanism to purge auto refreshing items that are no longer
 * used by users. Hence the size of the cache is unbounded if user keeps adding more
 * auto refreshing items.
 *
 */
class RefreshingCache[F[_]: Timer, K, V] private (
  innerCache: MemoryCache[F, K, V],
  refreshSemaphore: Semaphore[F],
  refreshTasks: Ref[F, Map[K, Fiber[F, Unit]]]
)(implicit F: Concurrent[F]) {
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
  def lookupOrFetch(k: K)(fetch: F[V]): F[V] =
    lookupOrFetch(defaultExpiration)(k)(fetch)

  def lookupOrFetch(timeout: Option[TimeSpec])(k: K)(fetch: F[V]): F[V] =
    lookup(k).flatMap {
      case Some(v) => v.pure[F]
      case None => fetchAndInsert(k, fetch, timeout)
    }

  def defaultExpiration: Option[TimeSpec] =
    innerCache.defaultExpiration

  /**
   * Try look up k, if the key is not present it will setup an auto {@code refresh} every {@code period}
   * This method is idempotent and always returns as expected.
   * Note, by design, it does not override an existing refresh effect for the same k.
   * To setup a new refresh method for it, you must
   * first {@code delete(k)} and then call this method.
   * If refresh keeps failing, the value will eventually expire and the refresh stops
   */
  def lookupOrRefresh(k: K, period: TimeSpec)(refresh: F[V]): F[V] =
    lookupOrRefresh(k, period, defaultExpiration)(refresh)(PartialFunction.empty)

  def lookupOrRefresh(k: K,
                      period: TimeSpec,
                      timeout: Option[TimeSpec])
                     (refresh: F[V])
                     (recoverError: PartialFunction[Throwable, F[Unit]]): F[V] =
    lookup(k).flatMap {
      case Some(v) => v.pure[F]
      case None => setupRefresh(k,
        refresh,
        period,
        timeout,
        recoverError) *> fetchAndInsert(k, refresh, timeout)
    }


  private def setupRefresh(k: K,
                           fetch: F[V],
                           period: TimeSpec,
                           timeout: Option[TimeSpec],
                           recoverError: PartialFunction[Throwable, F[Unit]]): F[Unit] = {

    def loop(): F[Unit] = {
      def doRefreshFetch = {
        lookup(k).flatMap[RefreshResult] {
          case Some(_) => fetch.map[RefreshResult](Success(_))
            .recoverWith(recoverError.andThen(_.as(ErrorRecovered))).recover {
            case _ => ErrorUncovered
          }
          case None => NotFound.pure[F].widen
        }
      }

      for {
        fiber <- Concurrent[F].start(
          Timer[F].sleep(Duration.fromNanos(period.nanos)) >>
            doRefreshFetch
        )
        result <- fiber.join
        r <- result match {
          case Success(v) => insertWithTimeout(timeout)(k, v) >> loop()
          case ErrorRecovered => loop()
          case NotFound | ErrorUncovered => deregisterRefresh(k).void
        }
      } yield r
    }

    if (timeout.fold(false)(_.nanos < period.nanos))
      F.raiseError(RefreshDurationTooLong(period, timeout.get))
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

  def purgeExpired: F[Unit] =
    innerCache.purgeExpired

  private def deregisterRefresh(k: K): F[Option[Unit]] =
    refreshTasks.modify { m =>
      (m - k, m.get(k).traverse[F, Unit](_.cancel))
    }.flatten


  private def fetchAndInsert(k: K, fetch: F[V], timeout: Option[TimeSpec]): F[V] =
    for {
      v <- fetch
      _ <- insertWithTimeout(timeout)(k, v)
    } yield v

  sealed trait RefreshResult extends Serializable with Product

  case class Success(v: V) extends RefreshResult
  case object NotFound extends RefreshResult
  case object ErrorRecovered extends RefreshResult
  case object ErrorUncovered extends RefreshResult
}

object RefreshingCache {

  def create[F[_]: Concurrent: Timer, K, V](
      defaultExpiration: Option[TimeSpec]
  ): F[RefreshingCache[F, K, V]] =
    MemoryCache.createMemoryCache[F, K, V](defaultExpiration).flatMap(createUsing[F, K, V])


  case class RefreshDurationTooLong(duration: TimeSpec, expiration: TimeSpec)
      extends RuntimeException {
    override def getMessage: String =
      s"refreshing period ($duration) cannot be longer than expiration (${expiration})"
  }

  def createAutoPurging[F[_]: Concurrent: Timer, K, V](
      expiresIn: TimeSpec,
      checkOnExpirationsEvery: TimeSpec
    ): Resource[F, RefreshingCache[F, K, V]] =
    MemoryCache.createAutoMemoryCache(expiresIn, checkOnExpirationsEvery).evalMap(createUsing[F, K, V])

  private def createUsing[F[_]: Concurrent: Timer, K, V](
     innerCache: MemoryCache[F, K, V]
   ): F[RefreshingCache[F, K, V]] =
    for {
      s <- Semaphore(1)
      tasks <- Ref.of[F, Map[K, Fiber[F, Unit]]](Map.empty)
    } yield new RefreshingCache[F, K, V](innerCache, s, tasks)

}

