/*
 * Copyright (c) 2018 Christopher Davenport
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package io.chrisdavenport.mules

import cats._
import cats.effect._
import cats.effect.syntax.all._
import cats.syntax.all._
import scala.collection.immutable.Map

import cats.effect.std.MapRef

final class MemoryCache[F[_], K, V] private[MemoryCache] (
    private val mapRef: MapRef[F, K, Option[MemoryCache.MemoryCacheItem[V]]],
    private val purgeExpiredEntriesOpt: Option[
      Long => F[List[K]]
    ], // Optional Performance Improvement over Default
    val defaultExpiration: Option[TimeSpec],
    private val onInsert: (K, V) => F[Unit],
    private val onCacheHit: (K, V) => F[Unit],
    private val onCacheMiss: K => F[Unit],
    private val onDelete: K => F[Unit]
)(implicit val F: Temporal[F])
    extends Cache[F, K, V] {
  import MemoryCache.MemoryCacheItem
  private val noneF: F[None.type] = Applicative[F].pure(None)
  private def noneFA[A]: F[Option[A]] = noneF.asInstanceOf[F[Option[A]]]

  val purgeExpiredEntries: Long => F[List[K]] =
    purgeExpiredEntriesOpt.getOrElse((_: Long) => List.empty[K].pure[F])

  /**
   * Delete an item from the cache. Won't do anything if the item is not present.
   */
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
   * If the expiration value is None, the item will never expire. The default expiration value of
   * the cache is ignored.
   *
   * The expiration value is relative to the current clockMonotonic time, i.e. it will be
   * automatically added to the result of clockMonotonic for the supplied unit.
   */
  def insertWithTimeout(optionTimeout: Option[TimeSpec])(k: K, v: V): F[Unit] = {
    for {
      now <- Clock[F].monotonic
      timeout = optionTimeout.map(ts => TimeSpec.unsafeFromNanos(now.toNanos + ts.nanos))
      _ <- mapRef.setKeyValue(k, MemoryCacheItem[V](v, timeout))
      _ <- onInsert(k, v)
    } yield ()
  }

  /**
   * Return all keys present in the cache, including expired items.
   */
  // def keys: F[Chains[K]] = key
  // ref.get.map(_.keys.toList)

  /**
   * Lookup an item with the given key, and delete it if it is expired.
   *
   * The function will only return a value if it is present in the cache and if the item is not
   * expired.
   *
   * The function will eagerly delete the item from the cache if it is expired.
   */
  def lookup(k: K): F[Option[V]] = {
    Clock[F].monotonic
      .flatMap { now =>
        mapRef(k).modify[F[Option[MemoryCacheItem[V]]]] {
          case s @ Some(value) =>
            if (MemoryCache.isExpired(now.toNanos, value)) {
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
      .flatMap {
        case s @ Some(v) => onCacheHit(k, v).as(s)
        case None => onCacheMiss(k).as(None)
      }
  }

  /**
   * Lookup an item with the given key, but don't delete it if it is expired.
   *
   * The function will only return a value if it is present in the cache and if the item is not
   * expired.
   *
   * The function will not delete the item from the cache.
   */
  def lookupNoUpdate(k: K): F[Option[V]] =
    Clock[F].monotonic
      .flatMap { now =>
        mapRef(k).get.map(
          _.flatMap(ci =>
            Alternative[Option]
              .guard(
                !MemoryCache.isExpired(now.toNanos, ci)
              )
              .as(ci)
          )
        )
      }
      .map(_.map(_.item))
      .flatMap {
        case s @ Some(v) => onCacheHit(k, v).as(s)
        case None => onCacheMiss(k).as(None)
      }

  /**
   * Change the default expiration value of newly added cache items. Shares an underlying reference
   * with the other cache. Use copyMemoryCache if you want different caches.
   */
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
   * Delete all items that are expired.
   *
   * This is one big atomic operation.
   */
  def purgeExpired: F[Unit] = {
    for {
      now <- Clock[F].monotonic
      out <- purgeExpiredEntries(now.toNanos)
      _ <- out.traverse_(onDelete)
    } yield ()
  }

  /**
   * Reference to this MemoryCache with the `onCacheHit` effect being composed of the old and new
   * function.
   */
  def withOnCacheHit(onCacheHitNew: (K, V) => F[Unit]): MemoryCache[F, K, V] =
    new MemoryCache[F, K, V](
      mapRef,
      purgeExpiredEntriesOpt,
      defaultExpiration,
      onInsert,
      { (k, v) => onCacheHit(k, v) >> onCacheHitNew(k, v) },
      onCacheMiss,
      onDelete
    )

  /**
   * Reference to this MemoryCache with the `onCacheMiss` effect being composed of the old and new
   * function.
   */
  def withOnCacheMiss(onCacheMissNew: K => F[Unit]): MemoryCache[F, K, V] =
    new MemoryCache[F, K, V](
      mapRef,
      purgeExpiredEntriesOpt,
      defaultExpiration,
      onInsert,
      onCacheHit,
      { k => onCacheMiss(k) >> onCacheMissNew(k) },
      onDelete
    )

  /**
   * Reference to this MemoryCache with the `onDelete` effect being composed of the old and new
   * function.
   */
  def withOnDelete(onDeleteNew: K => F[Unit]): MemoryCache[F, K, V] =
    new MemoryCache[F, K, V](
      mapRef,
      purgeExpiredEntriesOpt,
      defaultExpiration,
      onInsert,
      onCacheHit,
      onCacheMiss,
      { k => onDelete(k) >> onDeleteNew(k) }
    )

  /**
   * Reference to this MemoryCache with the `onInsert` effect being composed of the old and new
   * function.
   */
  def withOnInsert(onInsertNew: (K, V) => F[Unit]): MemoryCache[F, K, V] =
    new MemoryCache[F, K, V](
      mapRef,
      purgeExpiredEntriesOpt,
      defaultExpiration,
      { (k, v) => onInsert(k, v) >> onInsertNew(k, v) },
      onCacheHit,
      onCacheMiss,
      onDelete
    )

}

object MemoryCache {
  case class MemoryCacheItem[A](
      item: A,
      itemExpiration: Option[TimeSpec]
  )

  /**
   * Initiates a background process that checks for expirations every certain amount of time.
   *
   * @param memoryCache:
   *   The cache to check and expire automatically.
   * @param checkOnExpirationsEvery:
   *   How often the expiration process should check for expired keys.
   *
   * @return
   *   an `Resource[F, Unit]` that will keep removing expired entries in the background.
   */
  def liftToAuto[F[_]: Temporal, K, V](
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
   * Cache construction is Synchronous
   *
   * Otherwise a copy paste of {@link #ofSingleImmutableMap() ofSingleImmutableMap}
   */
  def inSingleImmutableMap[G[_]: Sync, F[_]: Async, K, V](
      defaultExpiration: Option[TimeSpec]
  ): G[MemoryCache[F, K, V]] =
    Ref
      .in[G, F, Map[K, MemoryCacheItem[V]]](Map.empty[K, MemoryCacheItem[V]])
      .map(ref =>
        new MemoryCache[F, K, V](
          MapRef.fromSingleImmutableMapRef(ref),
          { (l: Long) =>
            SingleRef.purgeExpiredEntries[F, K, MemoryCacheItem[V]](ref, isExpired)(l)
          }.some,
          defaultExpiration,
          { (_, _) => Concurrent[F].unit },
          { (_, _) => Concurrent[F].unit },
          { (_: K) => Concurrent[F].unit },
          { (_: K) => Concurrent[F].unit }
        )
      )

  /**
   * Create a new cache with a default expiration value for newly added cache items.
   *
   * Items that are added to the cache without an explicit expiration value (using insert) will be
   * inserted with the default expiration value.
   *
   * If the specified default expiration value is None, items inserted by insert will never expire.
   */
  def ofSingleImmutableMap[F[_]: Temporal, K, V](
      defaultExpiration: Option[TimeSpec]
  ): F[MemoryCache[F, K, V]] =
    Ref
      .of[F, Map[K, MemoryCacheItem[V]]](Map.empty[K, MemoryCacheItem[V]])
      .map(ref =>
        new MemoryCache[F, K, V](
          MapRef.fromSingleImmutableMapRef(ref),
          { (l: Long) =>
            SingleRef.purgeExpiredEntries[F, K, MemoryCacheItem[V]](ref, isExpired)(l)
          }.some,
          defaultExpiration,
          { (_, _) => Concurrent[F].unit },
          { (_, _) => Concurrent[F].unit },
          { (_: K) => Concurrent[F].unit },
          { (_: K) => Concurrent[F].unit }
        )
      )

  def ofShardedImmutableMap[F[_]: Temporal, K, V](
      shardCount: Int,
      defaultExpiration: Option[TimeSpec]
  ): F[MemoryCache[F, K, V]] =
    PurgeableMapRef.ofShardedImmutableMap[F, K, MemoryCacheItem[V]](shardCount, isExpired).map {
      smr =>
        new MemoryCache[F, K, V](
          smr.mapRef,
          Some(smr.purgeExpiredEntries),
          defaultExpiration,
          { (_, _) => Concurrent[F].unit },
          { (_, _) => Concurrent[F].unit },
          { (_: K) => Applicative[F].unit },
          { (_: K) => Applicative[F].unit }
        )
    }

  def ofConcurrentHashMap[F[_]: Async, K, V](
      defaultExpiration: Option[TimeSpec],
      initialCapacity: Int = 16,
      loadFactor: Float = 0.75f,
      concurrencyLevel: Int = 16
  ): F[MemoryCache[F, K, V]] =
    PurgeableMapRef
      .ofConcurrentHashMap[F, K, MemoryCacheItem[V]](
        initialCapacity,
        loadFactor,
        concurrencyLevel,
        isExpired
      )
      .map { pmr =>
        new MemoryCache[F, K, V](
          pmr.mapRef,
          Some(pmr.purgeExpiredEntries),
          defaultExpiration,
          { (_, _) => Applicative[F].unit },
          { (_, _) => Applicative[F].unit },
          { (_: K) => Applicative[F].unit },
          { (_: K) => Applicative[F].unit }
        )
      }

  def ofMapRef[F[_]: Temporal, K, V](
      mr: MapRef[F, K, Option[MemoryCacheItem[V]]],
      defaultExpiration: Option[TimeSpec]
  ): MemoryCache[F, K, V] = {
    new MemoryCache[F, K, V](
      mr,
      None,
      defaultExpiration,
      { (_, _) => Applicative[F].unit },
      { (_, _) => Applicative[F].unit },
      { (_: K) => Applicative[F].unit },
      { (_: K) => Applicative[F].unit }
    )
  }

  private def isExpired[A](checkAgainst: Long, cacheItem: MemoryCacheItem[A]): Boolean = {
    cacheItem.itemExpiration match {
      case Some(e) if e.nanos < checkAgainst => true
      case _ => false
    }
  }
}
