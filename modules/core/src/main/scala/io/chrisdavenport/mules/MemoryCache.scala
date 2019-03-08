package io.chrisdavenport.mules

import cats.data._
import cats.effect._
// For Cats-effect 1.0
import cats.effect.concurrent.Ref
import cats.effect.syntax.concurrent._
import cats.implicits._
import scala.concurrent.duration._
import scala.collection.immutable.Map

final class MemoryCache[F[_], K, V] private[MemoryCache] (
  private val ref: Ref[F, Map[K, MemoryCache.MemoryCacheItem[V]]], 
  val defaultExpiration: Option[TimeSpec],
  private val onInsert: (K, V) => F[Unit],
  private val onCacheHit: (K, V) => F[Unit],
  private val onCacheMiss: K => F[Unit],
  private val onDelete: K => F[Unit]
)(implicit val F: Sync[F], val C: Clock[F]) extends Cache[F, K, V] {
  import MemoryCache.MemoryCacheItem

  /**
   * Delete an item from the cache. Won't do anything if the item is not present.
   **/
  def delete(k: K): F[Unit] = 
    ref.update(m => m - (k)).void <* onDelete(k)

  /**
   * Insert an item in the cache, using the default expiration value of the cache.
   */
  def insert(k: K, v: V): F[Unit] =
    insertWithTimeout(defaultExpiration)(k, v)

  /**
   * Insert an item in the cache, with an explicit expiration value.
   *
   * If the expiration value is None, the item will never expire. The default expiration value of the cache is ignored.
   * 
   * The expiration value is relative to the current clockMonotonic time, i.e. it will be automatically added to the result of clockMonotonic for the supplied unit.
   **/
  def insertWithTimeout(optionTimeout: Option[TimeSpec])(k: K, v: V): F[Unit] = {
    for {
      now <- C.monotonic(NANOSECONDS)
      timeout = optionTimeout.map(ts => TimeSpec.unsafeFromNanos(now + ts.nanos))
      _ <- ref.update(m => m + (k -> MemoryCacheItem[V](v, timeout)))
      _ <- onInsert(k, v)
    } yield ()
  }
    

  private def isExpired[A](checkAgainst: TimeSpec, cacheItem: MemoryCacheItem[A]): Boolean = {
    cacheItem.itemExpiration.fold(false){
      case e if e.nanos < checkAgainst.nanos => true
      case _ => false
    }
  }

  /**
   * Return all keys present in the cache, including expired items.
   **/
  def keys: F[List[K]] = 
    ref.get.map(_.keys.toList)

  /**
   * Lookup an item with the given key, and delete it if it is expired.
   * 
   * The function will only return a value if it is present in the cache and if the item is not expired.
   * 
   * The function will eagerly delete the item from the cache if it is expired.
   **/
  def lookup(k: K): F[Option[V]] = 
    C.monotonic(NANOSECONDS)
      .flatMap(now => lookupItemT(true, k, TimeSpec.unsafeFromNanos(now)))
      .map(_.map(_.item))
      .flatMap{
        case s@Some(v) => onCacheHit(k, v).as(s)
        case n@None => onCacheMiss(k).as(n)
      }

  private def lookupItemSimple(k: K): F[Option[MemoryCacheItem[V]]] = 
    ref.get.map(_.get(k))

  /**
   * Internal Function Used for Lookup and management of values.
   * If isExpired and The boolean for delete is present then we delete,
   * otherwise return the value.
   **/
  private def lookupItemT(del: Boolean, k: K, t: TimeSpec): F[Option[MemoryCacheItem[V]]] = {
    val optionT = for {
      i <- OptionT(lookupItemSimple(k))
      e = isExpired(t, i)
      _ <- if (e && del) OptionT.liftF(delete(k)) else OptionT.some[F](())
      result <- if (e) OptionT.none[F, MemoryCacheItem[V]] else OptionT.some[F](i)
    } yield result
    optionT.value
  }

  /**
   * Lookup an item with the given key, but don't delete it if it is expired.
   *
   * The function will only return a value if it is present in the cache and if the item is not expired.
   *
   * The function will not delete the item from the cache.
   **/
  def lookupNoUpdate(k: K): F[Option[V]] = 
    C.monotonic(NANOSECONDS)
      .flatMap(now => lookupItemT(false, k, TimeSpec.unsafeFromNanos(now)))
      .map(_.map(_.item))
      .flatMap{
        case s@Some(v) => onCacheHit(k,v).as(s)
        case n@None => onCacheMiss(k).as(n)
      }

  /**
   * Change the default expiration value of newly added cache items. Shares an underlying reference
   * with the other cache. Use copyMemoryCache if you want different caches.
   **/
  def setDefaultExpiration(defaultExpiration: Option[TimeSpec]): MemoryCache[F, K, V] = 
    new MemoryCache[F, K, V](
      ref,
      defaultExpiration,
      onInsert,
      onCacheHit,
      onCacheMiss,
      onDelete
    )

  /**
   * Reference to this MemoryCache with the `onCacheHit` effect being the new function.
   */
  def setOnCacheHit(onCacheHitNew: (K, V) => F[Unit]): MemoryCache[F, K, V] = 
    new MemoryCache[F, K, V](
      ref,
      defaultExpiration,
      onInsert,
      onCacheHitNew,
      onCacheMiss,
      onDelete
    )

  /**
   * Reference to this MemoryCache with the `onCacheMiss` effect being the new function.
   */
  def setOnCacheMiss(onCacheMissNew: K => F[Unit]): MemoryCache[F, K, V] = 
    new MemoryCache[F, K, V](
      ref,
      defaultExpiration,
      onInsert,
      onCacheHit,
      onCacheMissNew,
      onDelete
    )
  /**
   * Reference to this MemoryCache with the `onDelete` effect being the new function.
   */
  def setOnDelete(onDeleteNew: K => F[Unit]): MemoryCache[F, K, V] = 
    new MemoryCache[F, K, V](
      ref,
      defaultExpiration,
      onInsert,
      onCacheHit,
      onCacheMiss,
      onDeleteNew
    )

  /**
   * Reference to this MemoryCache with the `onInsert` effect being the new function.
   */
  def setOnInsert(onInsertNew: (K, V) => F[Unit]): MemoryCache[F, K, V] = 
    new MemoryCache[F, K, V](
      ref,
      defaultExpiration,
      onInsertNew,
      onCacheHit,
      onCacheMiss,
      onDelete
    )


  /**
   * Return the size of the cache, including expired items.
   **/
  def size: F[Int] = 
    ref.get.map(_.size)

  /**
   * Delete all items that are expired.
   *
   * This is one big atomic operation.
   **/
  def purgeExpired: F[Unit] = {
    def purgeKeyIfExpired(m: Map[K, MemoryCacheItem[V]], k: K, checkAgainst: TimeSpec): (Map[K, MemoryCacheItem[V]], Option[K]) = 
      m.get(k)
        .map(item => 
          if (isExpired(checkAgainst, item)) ((m - (k)), k.some) 
          else (m, None)
        )
        .getOrElse((m, None))

    for {
      now <- C.monotonic(NANOSECONDS)
      chain <- ref.modify(
        m => {
          m.keys.toList
            .foldLeft((m, Chain.empty[K])){ case ((m, acc), k) => 
              val (mOut, maybeDeleted) = purgeKeyIfExpired(m, k, TimeSpec.unsafeFromNanos(now))
              maybeDeleted.fold((mOut, acc))(kv => (mOut, acc :+ kv))
            }
        }
      )// One Big Transactional Change
      _ <- chain.traverse_(onDelete)
    } yield ()
  }
  
  /**
   * Reference to this MemoryCache with the `onCacheHit` effect being composed of the old and new function.
   */
  def withOnCacheHit(onCacheHitNew: (K, V) => F[Unit]): MemoryCache[F, K, V] = 
    new MemoryCache[F, K, V](
      ref,
      defaultExpiration,
      onInsert,
      {(k, v) => onCacheHit(k, v) >> onCacheHitNew(k, v)},
      onCacheMiss,
      onDelete
    )

  /**
   * Reference to this MemoryCache with the `onCacheMiss` effect being composed of the old and new function.
   */
  def withOnCacheMiss(onCacheMissNew: K => F[Unit]): MemoryCache[F, K, V] = 
    new MemoryCache[F, K, V](
      ref,
      defaultExpiration,
      onInsert,
      onCacheHit,
      {k => onCacheMiss(k) >> onCacheMissNew(k)},
      onDelete
    )

  /**
   * Reference to this MemoryCache with the `onDelete` effect being composed of the old and new function.
   */
  def withOnDelete(onDeleteNew: K => F[Unit]): MemoryCache[F, K, V] = 
    new MemoryCache[F, K, V](
      ref,
      defaultExpiration,
      onInsert,
      onCacheHit,
      onCacheMiss,
      {k => onDelete(k) >> onDeleteNew(k)}
    )

  /**
   * Reference to this MemoryCache with the `onInsert` effect being composed of the old and new function.
   */
  def withOnInsert(onInsertNew: (K, V) => F[Unit]): MemoryCache[F, K, V] = 
    new MemoryCache[F, K, V](
      ref,
      defaultExpiration,
      {(k, v) => onInsert(k, v) >> onInsertNew(k, v)},
      onCacheHit,
      onCacheMiss,
      onDelete
    )

}

object MemoryCache {
  private case class MemoryCacheItem[A](
    item: A,
    itemExpiration: Option[TimeSpec]
  )

  /**
   * Creates a new cache with default expiration and automatic key-expiration support.
   *
   * It fires off a background process that checks for expirations every certain amount of time.
   *
   * @param expiresIn: the expiration time of every key-value in the MemoryCache.
   * @param checkOnExpirationsEvery: how often the expiration process should check for expired keys.
   *
   * @return an `[F[MemoryCache[F, K, V]]` that will create a MemoryCache with key-expiration support when evaluated.
   **/
  def createAutoMemoryCache[F[_]: Concurrent: Timer, K, V](
    expiresIn: TimeSpec,
    checkOnExpirationsEvery: TimeSpec
  ): Resource[F, MemoryCache[F, K, V]] = {
    def runExpiration(cache: MemoryCache[F, K, V]): F[Unit] = {
      val check = TimeSpec.toDuration(checkOnExpirationsEvery)
      Timer[F].sleep(check) >> cache.purgeExpired >> runExpiration(cache)
    }

    Resource(
      Ref.of[F, Map[K, MemoryCacheItem[V]]](Map.empty[K, MemoryCacheItem[V]])
        .map(ref => new MemoryCache[F, K, V](
          ref,
          Some(expiresIn), 
          {(_, _) => Sync[F].unit},
          {(_, _) => Sync[F].unit},
          {_: K => Sync[F].unit},
          {_: K => Sync[F].unit}
        ))
        .flatMap(cache => 
          runExpiration(cache).start.map(fiber => (cache, fiber.cancel))
        )
      )
    }

  /**
    * Create a new cache with a default expiration value for newly added cache items.
    * 
    * Items that are added to the cache without an explicit expiration value (using insert) will be inserted with the default expiration value.
    * 
    * If the specified default expiration value is None, items inserted by insert will never expire.
    **/
  def createMemoryCache[F[_]: Sync: Clock, K, V](
    defaultExpiration: Option[TimeSpec],
  ): F[MemoryCache[F, K, V]] = 
    Ref.of[F, Map[K, MemoryCacheItem[V]]](Map.empty[K, MemoryCacheItem[V]])
      .map(new MemoryCache[F, K, V](
        _, 
        defaultExpiration,
        {(_, _) => Sync[F].unit},
        {(_, _) => Sync[F].unit},
        {_: K => Sync[F].unit},
        {_: K => Sync[F].unit}
      ))

  /**
    * Create a deep copy of the cache.
    **/ 
  def copyMemoryCache[F[_]: Sync, K, V](cache: MemoryCache[F, K, V]): F[MemoryCache[F, K, V]] = for {
    current <- cache.ref.get
    ref <- Ref.of[F, Map[K, MemoryCacheItem[V]]](current)
  } yield new MemoryCache[F, K, V](
    ref,
    cache.defaultExpiration,
    cache.onInsert,
    cache.onCacheHit,
    cache.onCacheMiss,
    cache.onDelete
  )(cache.F, cache.C)

}