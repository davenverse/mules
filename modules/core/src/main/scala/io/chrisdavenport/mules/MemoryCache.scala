package io.chrisdavenport.mules

import cats._
import cats.effect._
import cats.effect.syntax.concurrent._
import cats.implicits._
import scala.concurrent.duration._
import scala.collection.immutable.Map

import io.chrisdavenport.mapref.MapRef
import io.chrisdavenport.mapref.implicits._

import java.util.concurrent.ConcurrentHashMap
import cats.effect.{ Ref, Temporal }

final class MemoryCache[F[_], K, V] private[MemoryCache] (
  private val mapRef: MapRef[F, K, Option[MemoryCache.MemoryCacheItem[V]]],
  private val purgeExpiredEntriesOpt : Option[Long => F[List[K]]], // Optional Performance Improvement over Default
  val defaultExpiration: Option[TimeSpec],
  private val onInsert: (K, V) => F[Unit],
  private val onCacheHit: (K, V) => F[Unit],
  private val onCacheMiss: K => F[Unit],
  private val onDelete: K => F[Unit]
)(implicit val F: Sync[F], val C: Clock[F]) extends Cache[F, K, V] {
  import MemoryCache.MemoryCacheItem
  private val noneF: F[None.type] = Applicative[F].pure(None)
  private def noneFA[A]: F[Option[A]] = noneF.widen

  private def purgeExpiredEntriesDefault(now: Long): F[List[K]] = {
    keys.flatMap(l =>
      l.flatTraverse(k =>
        mapRef(k).modify(optItem =>
          optItem.map(item =>
            if (MemoryCache.isExpired(now, item))
              (None, List(k))
            else
              (optItem, List.empty)
          ).getOrElse((optItem, List.empty))
        )
      )
    )
  }

  val purgeExpiredEntries: Long => F[List[K]] =
    purgeExpiredEntriesOpt.getOrElse(purgeExpiredEntriesDefault)

  val keys : F[List[K]] = mapRef.keys

  /**
   * Delete an item from the cache. Won't do anything if the item is not present.
   **/
  def delete(k: K): F[Unit] =
    mapRef.unsetKey(k) >> onDelete(k)

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
      _ <- mapRef.setKeyValue(k, MemoryCacheItem[V](v, timeout))
      _ <- onInsert(k, v)
    } yield ()
  }

  /**
   * Return all keys present in the cache, including expired items.
   **/
  // def keys: F[Chains[K]] = key
    // ref.get.map(_.keys.toList)

  /**
   * Lookup an item with the given key, and delete it if it is expired.
   *
   * The function will only return a value if it is present in the cache and if the item is not expired.
   *
   * The function will eagerly delete the item from the cache if it is expired.
   **/
  def lookup(k: K): F[Option[V]] = {
    C.monotonic(NANOSECONDS)
      .flatMap{now =>
        mapRef(k).modify[F[Option[MemoryCacheItem[V]]]]{
          case s@Some(value) =>
            if (MemoryCache.isExpired(now, value)){
              (None, onDelete(k).as(None))
            } else {
              (s, F.pure(s))
            }
          case None =>
            (None, noneFA)
        }
      }
      .flatten
      .map(_.map(_.item))
      .flatMap{
        case s@Some(v) => onCacheHit(k, v).as(s)
        case None => onCacheMiss(k).as(None)
      }
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
      .flatMap{now =>
        mapRef(k).get.map(
          _.flatMap(ci =>
            Alternative[Option].guard(
              !MemoryCache.isExpired(now, ci)
            ).as(ci)
          )
        )
      }
      .map(_.map(_.item))
      .flatMap{
        case s@Some(v) => onCacheHit(k,v).as(s)
        case None => onCacheMiss(k).as(None)
      }

  /**
   * Change the default expiration value of newly added cache items. Shares an underlying reference
   * with the other cache. Use copyMemoryCache if you want different caches.
   **/
  def setDefaultExpiration(defaultExpiration: Option[TimeSpec]): MemoryCache[F, K, V] =
    new MemoryCache[F, K, V](
      mapRef,
      purgeExpiredEntriesOpt,
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
      mapRef,
      purgeExpiredEntriesOpt,
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
      mapRef,
      purgeExpiredEntriesOpt,
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
      mapRef,
      purgeExpiredEntriesOpt,
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
      mapRef,
      purgeExpiredEntriesOpt,
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
    keys.map(_.size)

  /**
   * Delete all items that are expired.
   *
   * This is one big atomic operation.
   **/
  def purgeExpired: F[Unit] = {
    for {
      now <- C.monotonic(NANOSECONDS)
      out <- purgeExpiredEntries(now)
      _ <-  out.traverse_(onDelete)
    } yield ()
  }

  /**
   * Reference to this MemoryCache with the `onCacheHit` effect being composed of the old and new function.
   */
  def withOnCacheHit(onCacheHitNew: (K, V) => F[Unit]): MemoryCache[F, K, V] =
    new MemoryCache[F, K, V](
      mapRef,
      purgeExpiredEntriesOpt,
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
      mapRef,
      purgeExpiredEntriesOpt,
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
      mapRef,
      purgeExpiredEntriesOpt,
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
      mapRef,
      purgeExpiredEntriesOpt,
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
   *
   * Initiates a background process that checks for expirations every certain amount of time.
   *
   * @param memoryCache: The cache to check and expire automatically.
   * @param checkOnExpirationsEvery: How often the expiration process should check for expired keys.
   *
   * @return an `Resource[F, Unit]` that will keep removing expired entries in the background.
   **/
  def liftToAuto[F[_]: Concurrent: Temporal, K, V](
    memoryCache: MemoryCache[F, K, V],
    checkOnExpirationsEvery: TimeSpec
  ): Resource[F, Unit] = {
    def runExpiration(cache: MemoryCache[F, K, V]): F[Unit] = {
      val check = TimeSpec.toDuration(checkOnExpirationsEvery)
      Temporal[F].sleep(check) >> cache.purgeExpired >> runExpiration(cache)
    }

    Resource.make(runExpiration(memoryCache).start)(_.cancel).void
  }

  /**
    * Create a new cache with a default expiration value for newly added cache items.
    *
    * Items that are added to the cache without an explicit expiration value (using insert) will be inserted with the default expiration value.
    *
    * If the specified default expiration value is None, items inserted by insert will never expire.
    **/
  def ofSingleImmutableMap[F[_]: Sync: Clock, K, V](
    defaultExpiration: Option[TimeSpec]
  ): F[MemoryCache[F, K, V]] =
    Ref.of[F, Map[K, MemoryCacheItem[V]]](Map.empty[K, MemoryCacheItem[V]])
      .map(ref => new MemoryCache[F, K, V](
        MapRef.fromSingleImmutableMapRef(ref),
        {l: Long => SingleRef.purgeExpiredEntries(ref)(l)}.some,
        defaultExpiration,
        {(_, _) => Sync[F].unit},
        {(_, _) => Sync[F].unit},
        {_: K => Sync[F].unit},
        {_: K => Sync[F].unit}
      ))

  def ofShardedImmutableMap[F[_]: Sync : Clock, K, V](
    shardCount: Int,
    defaultExpiration: Option[TimeSpec]
  ): F[MemoryCache[F, K, V]] =
    MapRef.ofShardedImmutableMap[F, K, MemoryCacheItem[V]](shardCount).map{
      new MemoryCache[F, K, V](
        _,
        None,
        defaultExpiration,
        {(_, _) => Sync[F].unit},
        {(_, _) => Sync[F].unit},
        {_: K => Sync[F].unit},
        {_: K => Sync[F].unit}
      )
    }

  def ofConcurrentHashMap[F[_]: Sync: Clock, K, V](
    defaultExpiration: Option[TimeSpec],
    initialCapacity: Int = 16,
    loadFactor: Float = 0.75f,
    concurrencyLevel: Int = 16,
  ): F[MemoryCache[F, K, V]] = Sync[F].delay{
    val chm = new ConcurrentHashMap[K, MemoryCacheItem[V]](initialCapacity, loadFactor, concurrencyLevel)
    new MemoryCache[F, K, V](
      MapRef.fromConcurrentHashMap(chm),
      None,
      defaultExpiration,
      {(_, _) => Sync[F].unit},
      {(_, _) => Sync[F].unit},
      {_: K => Sync[F].unit},
      {_: K => Sync[F].unit}
    )
  }

  def ofMapRef[F[_]: Sync: Clock, K, V](
    mr: MapRef[F, K, Option[MemoryCacheItem[V]]],
    defaultExpiration: Option[TimeSpec]
  ): MemoryCache[F, K, V] = {
    new MemoryCache[F, K, V](
        mr,
        None,
        defaultExpiration,
        {(_, _) => Sync[F].unit},
        {(_, _) => Sync[F].unit},
        {_: K => Sync[F].unit},
        {_: K => Sync[F].unit}
      )
  }


  private object SingleRef {

    def purgeExpiredEntries[F[_], K, V](ref: Ref[F, Map[K, MemoryCacheItem[V]]])(now: Long): F[List[K]] = {
      ref.modify(
        m => {
          val l = scala.collection.mutable.ListBuffer.empty[K]
          m.foreach{ case (k, item) =>
            if (isExpired(now, item)) {
              l.+=(k)
            }
          }
          val remove = l.result()
          val finalMap = m -- remove
          (finalMap, remove)
        }
      )
    }
  }

  private def isExpired[A](checkAgainst: Long, cacheItem: MemoryCacheItem[A]): Boolean = {
    cacheItem.itemExpiration match{
      case Some(e) if e.nanos < checkAgainst => true
      case _ => false
    }
  }
}
