package io.chrisdavenport.mules.reload

import cats.Monad
import cats.effect.concurrent.{Ref, Semaphore}
import cats.effect.{Concurrent, Fiber, Timer, _}
import cats.implicits._
import io.chrisdavenport.mules._
import io.chrisdavenport.mules.reload.AutoFetchingCache.Refresh

import scala.collection.immutable.Map
import scala.concurrent.duration.{Duration, _}
import scala.util.control.NonFatal

class AutoFetchingCache[F[_] : Concurrent : Timer, K, V](private val values: Ref[F, Map[K, AutoFetchingCache.CacheContent[F, V]]],
                                                         val defaultExpiration: Option[TimeSpec],
                                                         private val refresh: Option[Refresh[F, K]],
                                                         val fetch: K => F[V]) {

  def lookup(k: K): F[V] = AutoFetchingCache.lookup(this)(k)
}

object AutoFetchingCache {


  object Refresh {

    case class Config(period: TimeSpec,
                      maxParallelRefresh: Option[Int] = None
                     )


    def setupRefresh[F[_], K, V](k: K, c: AutoFetchingCache[F, K, V])(implicit C: Concurrent[F],
                                                                      T: Timer[F]): F[Unit] = {

      c.refresh.map { r =>

        def loop0(): F[Unit] =
          C.start[V](T.sleep(Duration.fromNanos(r.period.nanos)) >> c.fetch(k))
            .flatMap { fiber =>
              insertFetching(k, c)(fiber) >> fiber.join >>= (insert(c)(k, _))
            } >> loop0()

        def loop(): F[Unit] =
          loop0().recoverWith {
            case NonFatal(_) => loop()
          }

        r match {
          case BoundedRefresh(_, s, tasks) =>

            def cancel(m: BoundedRefresh.RefreshMap[F, K], popped: Option[K]): F[BoundedRefresh.RefreshMap[F, K]] =
              (for {
                k <- popped
                (cpt, f) <- m.get(k)
              } yield
                if (cpt - 1 <= 0) f.cancel.as(m - k)
                else C.pure(m)).getOrElse(C.pure(m))

            for {
              _ <- s.acquire
              ts <- tasks.get
              (m, queue) = ts
              (newq, popped) = queue.push(k)
              m1 <- cancel(m, popped)
              m2 <- m1.get(k) match {
                case None => C.start(loop()).map(f => m1 + (k -> ((1, f))))
                case Some((cpt, f)) => C.pure(m1 + (k -> ((cpt + 1, f))))
              }
              _ <- tasks.set((m2, newq))
              _ <- s.release
            } yield ()


          case UnboundedRefresh(_, s, tasks) =>

            for {
              _ <- s.acquire
              m <- tasks.get
              m1 <- m.get(k) match {
                case None => C.start(loop()).map(f => m + (k -> f))
                case Some(_) => C.pure(m)
              }
              _ <- tasks.set(m1)
              _ <- s.release
            } yield ()

        }
      }.getOrElse(C.unit)
    }
  }

  object BoundedRefresh {

    type RefreshMap[F[_], K] = Map[K, (Int, Fiber[F, Unit])]
    type Tasks[F[_], K] = (RefreshMap[F, K], BoundedQueue[K])

    def cancelAll[F[_] : Monad, K](refresh: BoundedRefresh[F, K]): F[Unit] =
      refresh.tasks.modify { case (m, q) =>
        ((Map.empty, BoundedQueue.empty(q.maxSize)),
          m.values.toList.traverse[F, Unit] { case (_, f) => f.cancel }.void)
      }.flatten


  }

  object UnboundedRefresh {
    type Tasks[F[_], K] = Map[K, Fiber[F, Unit]]

    def cancelAll[F[_] : Monad, K](refresh: UnboundedRefresh[F, K]): F[Unit] =
      refresh.tasks.modify { m =>
        (Map.empty,
          m.values.toList.traverse[F, Unit](_.cancel).void)
      }.flatten
  }

  sealed trait Refresh[F[_], K] {
    def period: TimeSpec

    def cancelAll: F[Unit]
  }

  case class BoundedRefresh[F[_] : Monad, K](period: TimeSpec,
                                             s: Semaphore[F],
                                             tasks: Ref[F, BoundedRefresh.Tasks[F, K]]) extends Refresh[F, K] {

    def cancelAll: F[Unit] = BoundedRefresh.cancelAll(this)

  }

  case class UnboundedRefresh[F[_] : Monad, K](period: TimeSpec,
                                               s: Semaphore[F],
                                               tasks: Ref[F, UnboundedRefresh.Tasks[F, K]]
                                              ) extends Refresh[F, K] {
    def cancelAll: F[Unit] = UnboundedRefresh.cancelAll(this)
  }


  private sealed abstract class CacheContent[F[_], A]

  private case class Fetching[F[_], A](f: Fiber[F, A]) extends CacheContent[F, A]


  private case class CacheItem[F[_], A](
                                         item: A,
                                         itemExpiration: Option[TimeSpec]
                                       ) extends CacheContent[F, A]


  /**
    * Create a new cache with a default expiration value for newly added cache items.
    *
    * Items that are added to the cache without an explicit expiration value (using insert) will be inserted with the default expiration value.
    *
    * If the specified default expiration value is None, items inserted by insert will never expire.
    **/
  def createCache[F[_] : Concurrent : Timer, K, V](defaultExpiration: Option[TimeSpec],
                                                   refreshConfig: Option[Refresh.Config])
                                                  (fetch: K => F[V]): F[AutoFetchingCache[F, K, V]] =
    for {
      valuesRef <- Ref.of[F, Map[K, CacheContent[F, V]]](Map.empty)
      s <- Semaphore(1)
      refresh <- refreshConfig.traverse[F, Refresh[F, K]] { conf =>
        conf.maxParallelRefresh match {
        case Some(maxParallel) =>
          Ref.of[F, BoundedRefresh.Tasks[F, K]]((Map.empty, BoundedQueue.empty(maxParallel)))
            .map(ref =>
              BoundedRefresh(conf.period, s, ref)
            )
        case None =>
          Ref.of[F, UnboundedRefresh.Tasks[F, K]](Map.empty)
            .map(ref =>
              UnboundedRefresh(conf.period, s, ref)
            )
      }}

    } yield new AutoFetchingCache[F, K, V](valuesRef, defaultExpiration, refresh, fetch)


  /**
    * Return the size of the cache, including expired items.
    **/
  def size[F[_] : Sync, K, V](cache: AutoFetchingCache[F, K, V]): F[Int] =
    cache.values.get.map(_.size)

  /**
    * Return all keys present in the cache, including expired items.
    **/
  def keys[F[_] : Sync, K, V](cache: AutoFetchingCache[F, K, V]): F[List[K]] =
    cache.values.get.map(_.keys.toList)

  /**
    * Delete an item from the cache. Won't do anything if the item is not present.
    **/
  // implementing delete require for the bounded refresh that `type RefreshMap[F[_], K] = Map[K, (Int, Option[Fiber[F, Unit]])]`
  // to correctly handle the counter without having to remove every k in the boundedQueue
  //  def delete[F[_], K, V](cache: AutoFetchingCache[F, K, V])(k: K)
  //                        (implicit S: Sync[F]): F[Unit] =
  //    cache.values.modify(m =>
  //      (m - k, m.get(k) match {
  //        case Some(Fetching(f)) => f.cancel
  //        case _ => S.unit
  //      })) >> cache.refresh.traverse { refresh =>
  //      refresh.tasks.modify(m => (m - k,
  //        m.get(k) match {
  //          case Some((_, f)) => f.cancel
  //          case None => S.unit
  //        }
  //      ))
  //    }.void


  def cancelReloads[F[_] : Monad, K, V](cache: AutoFetchingCache[F, K, V]): F[Unit] =
    cache.refresh.map(_.cancelAll).getOrElse(Monad[F].unit)

  /**
    * Insert an item in the cache, using the default expiration value of the cache.
    */
  private def insert[F[_] : Sync : Timer, K, V](cache: AutoFetchingCache[F, K, V])(k: K, v: V): F[Unit] =
    insertWithTimeout(cache)(cache.defaultExpiration)(k, v)


  /**
    * Insert an item in the cache, with an explicit expiration value.
    *
    * If the expiration value is None, the item will never expire. The default expiration value of the cache is ignored.
    *
    * The expiration value is relative to the current clockMonotonic time, i.e. it will be automatically added to the result of clockMonotonic for the supplied unit.
    **/

  def insertWithTimeout[F[_] : Sync, K, V](cache: AutoFetchingCache[F, K, V])
                                          (optionTimeout: Option[TimeSpec])
                                          (k: K, v: V)
                                          (implicit T: Timer[F]): F[Unit] = {
    for {
      now <- T.clock.monotonic(NANOSECONDS)
      timeout = optionTimeout.map(ts => TimeSpec.unsafeFromNanos(now + ts.nanos))
      _ <- cache.values.update(_ + (k -> CacheItem[F, V](v, timeout)))
    } yield ()
  }


  private def insertFetching[F[_], K, V](k: K, cache: AutoFetchingCache[F, K, V])
                                        (f: Fiber[F, V]): F[Unit] = {
    cache.values.update(_ + (k -> Fetching[F, V](f)))
  }


  private def isExpired[F[_], A](checkAgainst: TimeSpec, cacheItem: CacheItem[F, A]): Boolean = {
    cacheItem.itemExpiration.fold(false) {
      case e if e.nanos < checkAgainst.nanos => true
      case _ => false
    }
  }

  private def lookupItemSimple[F[_] : Sync, K, V](k: K, c: AutoFetchingCache[F, K, V]): F[Option[CacheContent[F, V]]] =
    c.values.get.map(_.get(k))

  private def fetchAndInsert[F[_] : Sync : Timer, K, V](k: K, c: AutoFetchingCache[F, K, V]): F[V] =
    for {
      v <- c.fetch(k)
      _ <- insert(c)(k, v)
    } yield v


  private def extractContentT[F[_] : Timer, K, V](k: K, c: AutoFetchingCache[F, K, V], t: TimeSpec)
                                                 (content: CacheContent[F, V])
                                                 (implicit S: Sync[F]): F[V] = content match {
    case v0: Fetching[F, V] => v0.f.join
    case v0: CacheItem[F, V] =>
      if (isExpired(t, v0)) fetchAndInsert(k, c)
      else S.pure(v0.item)
  }

  private def lookupItemT[F[_] : Timer, K, V](k: K, c: AutoFetchingCache[F, K, V], t: TimeSpec)
                                             (implicit C: Concurrent[F]): F[V] = {
    for {
      _ <- Refresh.setupRefresh(k, c)
      i <- lookupItemSimple(k, c)
      v <- i match {
        case None => fetchAndInsert(k, c)
        case Some(content) => extractContentT(k, c, t)(content)
      }
    } yield v
  }


  def lookup[F[_] : Concurrent, K, V](c: AutoFetchingCache[F, K, V])
                                     (k: K)
                                     (implicit T: Timer[F]): F[V] =
    T.clock.monotonic(NANOSECONDS)
      .flatMap(now => lookupItemT(k, c, TimeSpec.unsafeFromNanos(now)))

}
