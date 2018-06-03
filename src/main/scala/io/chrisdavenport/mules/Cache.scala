package io.chrisdavenport.mules

// import cats._
import cats.data.OptionT
import cats.effect.{Sync, Timer}
import cats.effect.concurrent.Ref
import cats.implicits._
import scala.concurrent.duration._
import scala.collection.mutable.Map

object Cache {
  case class Cache[F[_], K, V](ref: Ref[F, Map[K, CacheItem[V]]], defaultExpiration: Option[TimeSpec])
  // Value of Time In Nanoseconds
  case class TimeSpec(
    nanos: Long
  ) extends AnyVal
  object TimeSpec {

  }
  case class CacheItem[A](
    item: A,
    itemExpiration: Option[TimeSpec]
  )

  /**
    * Create a new cache with a default expiration value for newly added cache items.
    * 
    * Items that are added to the cache without an explicit expiration value (using insert) will be inserted with the default expiration value.
    * 
    * If the specified default expiration value is None, items inserted by insert will never expire.
    **/
  def createCache[F[_]: Sync, K, V](defaultExpiration: Option[TimeSpec]): F[Cache[F, K, V]] = 
    Ref.of[F, Map[K, CacheItem[V]]](Map.empty[K, CacheItem[V]]).map(Cache[F, K, V](_, defaultExpiration))

  /**
    * Change the default expiration value of newly added cache items.
    **/
  def setDefaultExpiration[F[_], K, V](cache: Cache[F, K, V], defaultExpiration: Option[TimeSpec]): Cache[F, K, V] = 
    cache.copy(defaultExpiration = defaultExpiration)

  /**
    * Create a deep copy of the cache.
    **/ 
  def copyCache[F[_]: Sync, K, V](cache: Cache[F, K, V]): F[Cache[F, K, V]] = for {
    current <- cache.ref.get
    cache <- Ref.of[F, Map[K, CacheItem[V]]](current).map(Cache[F, K, V](_, cache.defaultExpiration))
  } yield cache


  /**
    * Insert an item in the cache, using the default expiration value of the cache.
    */
  def insert[F[_]: Sync : Timer, K, V](cache: Cache[F, K, V])(k: K, v: V): F[Unit] =
    insertWithTimeout(cache)(cache.defaultExpiration)(k, v)

  /**
    * Insert an item in the cache, with an explicit expiration value.
    *
    * If the expiration value is None, the item will never expire. The default expiration value of the cache is ignored.
    * 
    * The expiration value is relative to the current clockMonotonic time, i.e. it will be automatically added to the result of clockMonotonic for the supplied unit.
    **/
  def insertWithTimeout[F[_]: Sync: Timer, K, V](cache: Cache[F, K, V])(optionTimeout: Option[TimeSpec])(k: K, v: V): F[Unit] =
    for {
      now <- Timer[F].clockMonotonic(NANOSECONDS)
      timeout = optionTimeout.map(ts => ts.copy(nanos = now + ts.nanos))
      _ <- cache.ref.update(_.+((k -> CacheItem[V](v, timeout))))
    } yield ()
    

  /**
    * Return the size of the cache, including expired items.
    **/
  def size[F[_]: Sync, K, V](cache: Cache[F, K, V]): F[Int] = 
    cache.ref.get.map(_.size)

  /**
    * Return all keys present in the cache.
    **/
  def keys[F[_]: Sync, K, V](cache: Cache[F, K, V]): F[List[K]] = 
    cache.ref.get.map(_.keys.toList)

  /**
    * Delete an item from the cache. Won't do anything if the item is not present.
    **/
  def delete[F[_]: Sync, K, V](cache: Cache[F, K, V])(k: K): F[Unit] = 
    cache.ref.update(_.-(k))

  def isExpired[A](checkAgainst: TimeSpec, cacheItem: CacheItem[A]): Boolean = {
    cacheItem.itemExpiration.fold(false){
      case e if e.nanos < checkAgainst.nanos => true
      case _ => false
    }
  }

  private def lookupItemSimple[F[_]: Sync, K, V](k: K, c: Cache[F, K, V]): F[Option[CacheItem[V]]] = 
    c.ref.get.map(_.get(k))

  private def lookupItemT[F[_]: Sync, K, V](del: Boolean, k: K, c: Cache[F, K, V], t: TimeSpec): F[Option[CacheItem[V]]] = {
    val optionT = for {
      i <- OptionT(lookupItemSimple(k, c))
      e = isExpired(t, i)
      _ <- if (e && del) OptionT.liftF(delete(c)(k)) else OptionT.some[F](())
      result <- if (e) OptionT.none[F, CacheItem[V]] else OptionT.some[F](i)
    } yield result
    optionT.value
  }

  /**
    * Lookup an item with the given key, and delete it if it is expired.
    * 
    * The function will only return a value if it is present in the cache and if the item is not expired.
    * 
    * The function will eagerly delete the item from the cache if it is expired.
    **/
  def lookup[F[_]: Sync : Timer, K, V](k: K, c: Cache[F, K, V]): F[Option[CacheItem[V]]] = 
    Timer[F].clockMonotonic(NANOSECONDS).flatMap(now => lookupItemT(true, k, c, TimeSpec(now)))

  /**
    * Lookup an item with the given key, but don't delete it if it is expired.
    *
    * The function will only return a value if it is present in the cache and if the item is not expired.
    *
    * The function will not delete the item from the cache.
    **/
  def lookupNoUpdate[F[_]: Sync: Timer, K, V](k: K, c: Cache[F, K, V]): F[Option[CacheItem[V]]] = 
    Timer[F].clockMonotonic(NANOSECONDS).flatMap(now => lookupItemT(false, k, c, TimeSpec(now)))

  /**
    * Delete all items that are expired.
    **/
  def purgeExpired[F[_]: Sync: Timer, K, V](c: Cache[F, K, V]): F[Unit] =
    for {
      l <- keys(c)
      now <- Timer[F].clockMonotonic(NANOSECONDS)
      _ <- l.traverse_(k => lookupItemT(true, k,c, TimeSpec(now)))
    } yield ()


}