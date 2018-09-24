package io.chrisdavenport.mules

import cats.effect._
import cats.effect.concurrent.Ref
import cats.instances.option._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import io.chrisdavenport.mules.Cache.TimeSpec

import scala.collection.immutable.Map
import scala.concurrent.duration._

class AutoFetchingCache[F[_] : Concurrent : Timer, K, V](private val values: Ref[F, Map[K, AutoFetchingCache.CacheContent[F, V]]],
                                                         val defaultExpiration: Option[TimeSpec],
                                                         private val reload: Option[(TimeSpec, Ref[F, Map[K, Fiber[F, Unit]]])],
                                                         val fetch: K => F[V]) {

  def lookup(k: K): F[V] = AutoFetchingCache.lookup(this)(k)
}

object AutoFetchingCache {

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
                                                   defaultReloadTime: Option[TimeSpec])
                                                  (fetch: K => F[V]): F[AutoFetchingCache[F, K, V]] =
    for {
      valuesRef <- Ref.of[F, Map[K, CacheContent[F, V]]](Map.empty)
      reloads <- defaultReloadTime.traverse(timeSpec =>
        Ref.of[F, Map[K, Fiber[F, Unit]]](Map.empty).map((timeSpec, _))
      )
    } yield new AutoFetchingCache[F, K, V](valuesRef, defaultExpiration, reloads, fetch)


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
  def delete[F[_] : Sync, K, V](cache: AutoFetchingCache[F, K, V])(k: K): F[Unit] =
    cache.values.update(m => m - k).void


  /**
    * Insert an item in the cache, using the default expiration value of the cache.
    */
  def insert[F[_] : Sync : Timer, K, V](cache: AutoFetchingCache[F, K, V])(k: K, v: V): F[Unit] =
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


  private def autoReload[F[_], K, V](k: K, c: AutoFetchingCache[F, K, V])
                                    (implicit C: Concurrent[F],
                                     T: Timer[F]): F[Unit] =
    c.reload.map {
      case (tspec, reloads) =>

        def loop(): F[Unit] =
          C.start[V](T.sleep(Duration.fromNanos(tspec.nanos)) >> c.fetch(k))
            .flatMap { fiber =>
              insertFetching(k, c)(fiber) >> fiber.join >>= (insert(c)(k, _))
            } >> loop()


        val go: F[Unit] = reloads.get.map(_.contains(k)) >>= { alreadySetup =>
            if (alreadySetup) C.unit
            else C.start(loop()) // just in case a loop has already started we check the previous value
              .map(f => reloads.modify(m => (m + (k -> f), m.get(k)))).flatten
              .flatMap(fOpt => fOpt.map(_.cancel).getOrElse(C.unit))
          }

        go
    } getOrElse C.unit

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
      _ <- autoReload(k, c)
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
