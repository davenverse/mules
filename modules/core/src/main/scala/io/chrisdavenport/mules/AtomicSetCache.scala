package io.chrisdavenport.mules

import cats.effect._
import cats.effect.concurrent._
import cats.effect.implicits._
import cats.implicits._
import scala.concurrent.duration._
import scala.collection.immutable.Map

import io.chrisdavenport.mapref.MapRef

import java.util.concurrent.ConcurrentHashMap

final class AtomicSetCache[F[_], K, V] private[AtomicSetCache] (
  private val mapRef: MapRef[F, K, Option[AtomicSetCache.AtomicSetCacheItem[F, V]]],
  private val purgeExpiredEntriesOpt : Option[Long => F[List[K]]], // Optional Performance Improvement over Default
  val defaultExpiration: Option[TimeSpec],
  private val createItem: K => F[V]
)(implicit val F: Concurrent[F], val C: Clock[F]) extends GetCache[F, K, V] {
  import AtomicSetCache.AtomicSetCacheItem
  import AtomicSetCache.CancelationDuringAtomicSetCacheInsertProcessing

  private def purgeExpiredEntriesDefault(now: Long): F[List[K]] = {
    mapRef.keys.flatMap(l => 
      l.flatTraverse(k => 
        mapRef(k).modify(optItem => 
          optItem.map(item => 
            if (AtomicSetCache.isExpired(now, item)) 
              (None, List(k))
            else 
              (optItem, List.empty)
          ).getOrElse((optItem, List.empty))
        )
      )
    )
  }

  private val purgeExpiredEntries: Long => F[List[K]] = 
    purgeExpiredEntriesOpt.getOrElse(purgeExpiredEntriesDefault)

  private val emptyFV = F.pure(Option.empty[TryableDeferred[F, Either[Throwable, V]]])

  private val createEmptyIfUnset: K => F[Option[TryableDeferred[F, Either[Throwable, V]]]] = 
      k => Deferred.tryable[F, Either[Throwable, V]].flatMap{deferred => 
        C.monotonic(NANOSECONDS).flatMap{ now =>
        val timeout = defaultExpiration.map(ts => TimeSpec.unsafeFromNanos(now + ts.nanos))
        mapRef(k).modify{
          case None => (AtomicSetCacheItem[F, V](deferred, timeout).some, deferred.some)
          case s@Some(_) => (s, None)
        }}
      }

  private val updateIfFailedThenCreate: (K, AtomicSetCacheItem[F, V]) => F[Option[TryableDeferred[F, Either[Throwable, V]]]] = 
    (k, cacheItem) => cacheItem.item.tryGet.flatMap{
      case Some(Left(_)) => 
        mapRef(k).modify{ 
          case Some(cacheItemNow) if (cacheItem.itemExpiration === cacheItemNow.itemExpiration) =>
            (None, createEmptyIfUnset(k))
          case otherwise => 
            (otherwise, emptyFV)
        }.flatten
      case Some(Right(_)) | None => 
        emptyFV
    }

  private def insertAtomic(k: K): F[Unit] = {
    mapRef(k).modify{
      case None => 
        (None, createEmptyIfUnset(k))
      case s@Some(cacheItem) => 
        (s, updateIfFailedThenCreate(k, cacheItem))
    }.flatMap{ maybeDeferred => 
        maybeDeferred.bracketCase(_.traverse_{ deferred => 
          createItem(k).attempt.flatMap(e => deferred.complete(e).attempt.void)
        }){
          case (Some(deferred), ExitCase.Canceled) => deferred.complete(CancelationDuringAtomicSetCacheInsertProcessing.asLeft).attempt.void
          case (Some(deferred), ExitCase.Error(e)) => deferred.complete(e.asLeft).attempt.void
          case _ => F.unit
        }
    }
  }

  /**
   * Overrides any background insert
   **/
  def insert(k: K, v: V): F[Unit] = for {
    defered <- Deferred.tryable[F, Either[Throwable, V]]
    setAs = v.asRight
    _ <- defered.complete(setAs)
    now <- C.monotonic(NANOSECONDS)
    item = AtomicSetCacheItem(defered, defaultExpiration.map(spec => TimeSpec.unsafeFromNanos(now + spec.nanos))).some
    action <- mapRef(k).modify{
      case None => 
        (item, F.unit)
      case Some(it) => 
        (item, it.item.complete(setAs).attempt.void)
    }
    out <- action
  } yield out


  /**
   * Overrides any background insert
   **/
  def insertWithTimeout(optionTimeout: Option[TimeSpec])(k: K, v: V): F[Unit] = for {
    defered <- Deferred.tryable[F, Either[Throwable, V]]
    setAs = v.asRight
    _ <- defered.complete(setAs)
    now <- C.monotonic(NANOSECONDS)
    item = AtomicSetCacheItem(defered, optionTimeout.map(spec => TimeSpec.unsafeFromNanos(now + spec.nanos))).some
    action <- mapRef(k).modify{
      case None => 
        (item, F.unit)
      case Some(it) => 
        (item, it.item.complete(setAs).attempt.void)
    }
    out <- action
  } yield out

  def lookup(k: K): F[Option[V]] = get(k).map(_.some)

  def delete(k: K): F[Unit] = mapRef(k).set(None)

  /**
   * Lookup an item with the given key, and delete it if it is expired.
   * 
   * The function will only return a value if it is present in the cache and if the item is not expired.
   * 
   * The function will eagerly delete the item from the cache if it is expired.
   **/
  def get(k: K): F[V] = {
    C.monotonic(NANOSECONDS)
      .flatMap{now =>
        mapRef(k).modify[Option[AtomicSetCacheItem[F, V]]]{
          case s@Some(value) => 
            if (AtomicSetCache.isExpired(now, value)){
              (None, None)
            } else {
              (s, s)
            }
          case None => 
            (None, None)
        }
      }
      .flatMap{ 
        case Some(s) => s.item.get.flatMap{
          case Left(_) => insertAtomic(k) >> get(k)
          case Right(v) => F.pure(v)
        }
        case None => insertAtomic(k) >> get(k)
      }
  }

  /**
   * Change the default expiration value of newly added cache items. Shares an underlying reference
   * with the other cache. Use copyAtomicSetCache if you want different caches.
   **/
  def setDefaultExpiration(defaultExpiration: Option[TimeSpec]): AtomicSetCache[F, K, V] = 
    new AtomicSetCache[F, K, V](
      mapRef,
      purgeExpiredEntriesOpt,
      defaultExpiration,
      createItem
    )

  /**
   * Delete all items that are expired.
   **/
  def purgeExpired: F[Unit] = {
    for {
      now <- C.monotonic(NANOSECONDS)
      _ <- purgeExpiredEntries(now)
    } yield ()
  }

}

object AtomicSetCache {
  private case class AtomicSetCacheItem[F[_], A](
    item: TryableDeferred[F, Either[Throwable, A]],
    itemExpiration: Option[TimeSpec]
  )
  private case object CancelationDuringAtomicSetCacheInsertProcessing extends scala.util.control.NoStackTrace

  /**
   *
   * Initiates a background process that checks for expirations every certain amount of time.
   *
   * @param AtomicSetCache: The cache to check and expire automatically.
   * @param checkOnExpirationsEvery: How often the expiration process should check for expired keys.
   *
   * @return an `Resource[F, Unit]` that will keep removing expired entries in the background.
   **/
  def liftToAuto[F[_]: Concurrent: Timer, K, V](
    AtomicSetCache: AtomicSetCache[F, K, V],
    checkOnExpirationsEvery: TimeSpec
  ): Resource[F, Unit] = {
    def runExpiration(cache: AtomicSetCache[F, K, V]): F[Unit] = {
      val check = TimeSpec.toDuration(checkOnExpirationsEvery)
      Timer[F].sleep(check) >> cache.purgeExpired >> runExpiration(cache)
    }

    Resource.make(runExpiration(AtomicSetCache).start)(_.cancel).void
  }

  /**
    * Create a new cache with a default expiration value for newly added cache items.
    * 
    * If the specified default expiration value is None, items inserted by insert will never expire.
    **/
  def ofSingleImmutableMap[F[_]: Concurrent: Clock, K, V](
    createAction: K => F[V],
    defaultExpiration: Option[TimeSpec]
  ): F[AtomicSetCache[F, K, V]] = 
    Ref.of[F, Map[K, AtomicSetCacheItem[F, V]]](Map.empty[K, AtomicSetCacheItem[F, V]])
      .map(ref => new AtomicSetCache[F, K, V](
        MapRef.fromSingleImmutableMapRef(ref),
        {l: Long => SingleRef.purgeExpiredEntries(ref)(l)}.some,
        defaultExpiration,
        createAction
      ))

  def ofShardedImmutableMap[F[_]: Concurrent : Clock, K, V](
    createAction: K => F[V],
    shardCount: Int,
    defaultExpiration: Option[TimeSpec]
  ): F[AtomicSetCache[F, K, V]] = 
    MapRef.ofShardedImmutableMap[F, K, AtomicSetCacheItem[F, V]](shardCount).map{
      new AtomicSetCache[F, K, V](
        _,
        None,
        defaultExpiration,
        createAction
      )
    }

  def ofConcurrentHashMap[F[_]: Concurrent: Clock, K, V](
    createAction: K => F[V],
    defaultExpiration: Option[TimeSpec],
    initialCapacity: Int = 16,
    loadFactor: Float = 0.75f,
    concurrencyLevel: Int = 16,
  ): F[AtomicSetCache[F, K, V]] = Sync[F].delay{
    val chm = new ConcurrentHashMap[K, AtomicSetCacheItem[F, V]](initialCapacity, loadFactor, concurrencyLevel)
    new AtomicSetCache[F, K, V](
      MapRef.fromConcurrentHashMap(chm),
      None,
      defaultExpiration,
      createAction
    )
  }

  def ofMapRef[F[_]: Concurrent: Clock, K, V](
    createAction: K => F[V],
    mr: MapRef[F, K, Option[AtomicSetCacheItem[F, V]]],
    defaultExpiration: Option[TimeSpec]
  ): AtomicSetCache[F, K, V] = {
    new AtomicSetCache[F, K, V](
        mr,
        None,
        defaultExpiration,
        createAction
      )
  }


  private object SingleRef {

    def purgeExpiredEntries[F[_], K, V](ref: Ref[F, Map[K, AtomicSetCacheItem[F, V]]])(now: Long): F[List[K]] = {
      ref.modify(
        m => {
          val l = scala.collection.mutable.ListBuffer.empty[K]
          m.foreach{ case (k, item) => 
            if (isExpired(now, item)) {
              l.+=(k)
            }
          }
          val remove = l.result
          val finalMap = m -- remove
          (finalMap, remove)
        }
      )
    }
  }  

  private def isExpired[F[_], A](checkAgainst: Long, cacheItem: AtomicSetCacheItem[F, A]): Boolean = {
    cacheItem.itemExpiration match{ 
      case Some(e) if e.nanos < checkAgainst => true
      case _ => false
    }
  }
}