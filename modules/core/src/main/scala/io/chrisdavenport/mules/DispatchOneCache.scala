package io.chrisdavenport.mules

import cats.effect._
import cats.effect.implicits._
import cats.implicits._

import scala.collection.immutable.Map
import cats.effect.std.MapRef
import cats.effect.std.MapRef.fromSeqRefs

import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable

final class DispatchOneCache[F[_], K, V] private[DispatchOneCache] (
  private val mapRef: MapRef[F, K, Option[DispatchOneCache.DispatchOneCacheItem[F, V]]],
  private val purgeExpiredEntriesOpt : Option[Long => F[List[K]]], // Optional Performance Improvement over Default
  val defaultExpiration: Option[TimeSpec]
)(implicit val F: Concurrent[F], val C: Clock[F]) extends Cache[F, K, V]{
  import DispatchOneCache.DispatchOneCacheItem
  import DispatchOneCache.CancelationDuringDispatchOneCacheInsertProcessing

  //Note the default will not actually purge any entries
  private val purgeExpiredEntries: Long => F[List[K]] =
    purgeExpiredEntriesOpt.getOrElse({(_: Long) => List.empty[K].pure[F]})

  private val emptyFV = F.pure(Option.empty[Deferred[F, Either[Throwable, V]]])

  private val createEmptyIfUnset: K => F[Option[Deferred[F, Either[Throwable, V]]]] =
      k => Deferred[F, Either[Throwable, V]].flatMap{deferred =>
        C.monotonic.flatMap{ now =>
        val timeout = defaultExpiration.map(ts => TimeSpec.unsafeFromNanos(now.toNanos + ts.nanos))
        mapRef(k).modify{
          case None => (DispatchOneCacheItem[F, V](deferred, timeout).some, deferred.some)
          case s@Some(_) => (s, None)
        }}
      }

  private val updateIfFailedThenCreate: (K, DispatchOneCacheItem[F, V]) => F[Option[Deferred[F, Either[Throwable, V]]]] =
    (k, cacheItem) => cacheItem.item.tryGet.flatMap{
      case Some(Left(_)) =>
        mapRef(k).modify{
          case Some(cacheItemNow) if (cacheItem.itemExpiration.map(_.nanos) === cacheItemNow.itemExpiration.map(_.nanos)) =>
            (None, createEmptyIfUnset(k))
          case otherwise =>
            (otherwise, emptyFV)
        }.flatten
      case Some(Right(_)) | None =>
        emptyFV
    }

  private def insertAtomic(k: K, action: K => F[V]): F[Option[Either[Throwable, V]]] = {
    mapRef(k).modify{
      case None =>
        (None, createEmptyIfUnset(k))
      case s@Some(cacheItem) =>
        (s, updateIfFailedThenCreate(k, cacheItem))
    }.flatMap{ maybeDeferred =>
        maybeDeferred.bracketCase(_.flatTraverse{ deferred =>
          action(k).attempt.flatMap(e => deferred.complete(e).attempt map {
            case Left(_) => Option.empty // Either.left[Throwable, V](err) //only happened if complete action fails
            case Right(_) => Option(e)
          })
        }){
          case (Some(deferred), Outcome.Canceled()) => deferred.complete(CancelationDuringDispatchOneCacheInsertProcessing.asLeft).attempt.void
          case (Some(deferred), Outcome.Errored(e)) => deferred.complete(e.asLeft).attempt.void
          case _ => F.unit
        }
    }
  }

  /**
   * Gives an atomic only once loading function, or
   * gets the value in the system
   **/
  def lookupOrLoad(k: K, action: K => F[V]): F[V] = {
    C.monotonic
      .flatMap{now =>
        mapRef(k).modify[Option[DispatchOneCacheItem[F, V]]]{
          case s@Some(value) =>
            if (DispatchOneCache.isExpired(now.toNanos, value)){
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
          case Left(_) => insertAtomic(k, action)
          case Right(v) => F.pure(Option(Either.right[Throwable,V](v)))
        }
        case None => insertAtomic(k, action)
      }
      .flatMap {
        case Some(res) => res match {
          case Right(v) => v.pure[F]
          case Left(err) => err match {
            case CancelationDuringDispatchOneCacheInsertProcessing => lookupOrLoad(k,action)
            case _ => F.raiseError(err)
          }
        }
        case _ => lookupOrLoad(k,action) //cache miss case?
      }
  }

  def insertWith(k: K, action: K => F[V]): F[Unit] = {
    for {
      defer <- Deferred[F, Either[Throwable, V]]
      now <- Clock[F].monotonic
      item = DispatchOneCacheItem(defer, defaultExpiration.map(spec => TimeSpec.unsafeFromNanos(now.toNanos + spec.nanos))).some
      out <- mapRef(k).getAndSet(item)
        .bracketCase{oldDeferOpt =>
          action(k).flatMap[Unit]{ a =>
            val set = a.asRight
            oldDeferOpt.traverse_(oldDefer => oldDefer.item.complete(set)).attempt >>
            defer.complete(set).void
          }
        }{
        case (_, Outcome.Succeeded(_)) => F.unit
        case (oldItem, Outcome.Canceled()) =>
          val set = CancelationDuringDispatchOneCacheInsertProcessing.asLeft
          oldItem.traverse_(_.item.complete(set)).attempt >> defer.complete(set).attempt.void
        case (oldItem, Outcome.Errored(e)) =>
          val set = e.asLeft
          oldItem.traverse_(_.item.complete(set)).attempt >> defer.complete(set).attempt.void
      }
    } yield out
  }

  /**
   * Overrides any background insert
   **/
  def insert(k: K, v: V): F[Unit] = for {
    defered <- Deferred[F, Either[Throwable, V]]
    setAs = v.asRight
    _ <- defered.complete(setAs)
    now <- C.monotonic
    item = DispatchOneCacheItem(defered, defaultExpiration.map(spec => TimeSpec.unsafeFromNanos(now.toNanos + spec.nanos))).some
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
    defered <- Deferred[F, Either[Throwable, V]]
    setAs = v.asRight
    _ <- defered.complete(setAs)
    now <- C.monotonic
    item = DispatchOneCacheItem(defered, optionTimeout.map(spec => TimeSpec.unsafeFromNanos(now.toNanos + spec.nanos))).some
    action <- mapRef(k).modify{
      case None =>
        (item, F.unit)
      case Some(it) =>
        (item, it.item.complete(setAs).attempt.void)
    }
    out <- action
  } yield out

  def lookup(k: K): F[Option[V]] = {
    C.monotonic
      .flatMap{now =>
        mapRef(k).modify[Option[DispatchOneCacheItem[F, V]]]{
          case s@Some(value) =>
            if (DispatchOneCache.isExpired(now.toNanos, value)){
              (None, None)
            } else {
              (s, s)
            }
          case None =>
            (None, None)
        }
      }
      .flatMap{
        case Some(s) => s.item.get.map{
          case Left(_) => None
          case Right(v) => v.some
        }
        case None => F.pure(None)
      }
  }

  def delete(k: K): F[Unit] = mapRef(k).set(None)

  /**
   * Change the default expiration value of newly added cache items. Shares an underlying reference
   * with the other cache. Use copyDispatchOneCache if you want different caches.
   **/
  def setDefaultExpiration(defaultExpiration: Option[TimeSpec]): DispatchOneCache[F, K, V] =
    new DispatchOneCache[F, K, V](
      mapRef,
      purgeExpiredEntriesOpt,
      defaultExpiration,
    )

  /**
   * Delete all items that are expired.
   **/
  def purgeExpired: F[Unit] = {
    for {
      now <- C.monotonic
      _ <- purgeExpiredEntries(now.toNanos)
    } yield ()
  }

}

object DispatchOneCache {
  case class DispatchOneCacheItem[F[_], A](
    item: Deferred[F, Either[Throwable, A]],
    itemExpiration: Option[TimeSpec]
  )
  case object CancelationDuringDispatchOneCacheInsertProcessing extends scala.util.control.NoStackTrace

  /**
   *
   * Initiates a background process that checks for expirations every certain amount of time.
   *
   * @param DispatchOneCache: The cache to check and expire automatically.
   * @param checkOnExpirationsEvery: How often the expiration process should check for expired keys.
   *
   * @return an `Resource[F, Unit]` that will keep removing expired entries in the background.
   **/
  def liftToAuto[F[_]: Temporal, K, V](
    DispatchOneCache: DispatchOneCache[F, K, V],
    checkOnExpirationsEvery: TimeSpec
  ): Resource[F, Unit] = {
    def runExpiration(cache: DispatchOneCache[F, K, V]): F[Unit] = {
      val check = TimeSpec.toDuration(checkOnExpirationsEvery)
      Temporal[F].sleep(check) >> cache.purgeExpired >> runExpiration(cache)
    }

    Resource.make(runExpiration(DispatchOneCache).start)(_.cancel).void
  }

  /**
    * Create a new cache with a default expiration value for newly added cache items.
    *
    * If the specified default expiration value is None, items inserted by insert will never expire.
    **/
  def ofSingleImmutableMap[F[_]: Async, K, V](
    defaultExpiration: Option[TimeSpec]
  ): F[DispatchOneCache[F, K, V]] =
    Ref.of[F, Map[K, DispatchOneCacheItem[F, V]]](Map.empty[K, DispatchOneCacheItem[F, V]])
      .map(ref => new DispatchOneCache[F, K, V](
        MapRef.fromSingleImmutableMapRef(ref),
        {(l: Long) => SingleRef.purgeExpiredEntries[F,K, DispatchOneCacheItem[F,V]](ref, isExpired)(l)}.some,
        defaultExpiration
      ))

  //We can't key the keys from the mapref construction, so we have to repeat the construction hereto retain access to the underlying data
  def ofShardedImmutableMap[F[_]: Async, K, V](
    shardCount: Int,
    defaultExpiration: Option[TimeSpec]
  ): F[DispatchOneCache[F, K, V]] = {
    PurgeableMapRef.ofShardedImmutableMap[F,K, DispatchOneCacheItem[F, V]](shardCount, isExpired).map{ smr =>
      new DispatchOneCache[F, K, V](
        smr.mapRef,
        Some(smr.purgeExpiredEntries),
        defaultExpiration
      )
    }
  }

  def ofConcurrentHashMap[F[_]: Async, K, V](
    defaultExpiration: Option[TimeSpec],
    initialCapacity: Int = 16,
    loadFactor: Float = 0.75f,
    concurrencyLevel: Int = 16,
  ): F[DispatchOneCache[F, K, V]] =
    PurgeableMapRef.ofConcurrentHashMap[F,K, DispatchOneCacheItem[F, V]](
      initialCapacity,
      loadFactor,
      concurrencyLevel,
      isExpired).map {pmr =>
        new DispatchOneCache(
          pmr.mapRef,
          Some(pmr.purgeExpiredEntries),
          defaultExpiration
        )
    }


  //No access to keys here by default, so cache entries will only be cleared on overwrite.
  // kept separate from method below for bincompat
  def ofMapRef[F[_]: Concurrent: Clock, K, V](
     mr: MapRef[F, K, Option[DispatchOneCacheItem[F, V]]],
     defaultExpiration: Option[TimeSpec]
   ): DispatchOneCache[F, K, V] = {
    new DispatchOneCache[F, K, V](
      mr,
      None,
      defaultExpiration
    )
  }
  def ofMapRef[F[_]: Concurrent: Clock, K, V](
    mr: MapRef[F, K, Option[DispatchOneCacheItem[F, V]]],
    defaultExpiration: Option[TimeSpec],
    purgeExpiredEntries: Option[Long => F[List[K]]]
  ): DispatchOneCache[F, K, V] = {
    new DispatchOneCache[F, K, V](
        mr,
        purgeExpiredEntries,
        defaultExpiration
      )
  }

  private[mules] def isExpired[F[_], A](checkAgainst: Long, cacheItem: DispatchOneCacheItem[F, A]): Boolean = {
    cacheItem.itemExpiration match{
      case Some(e) if e.nanos < checkAgainst => true
      case _ => false
    }
  }
}

private[mules] object SingleRef {
  def purgeExpiredEntries[F[_], K, V](ref: Ref[F, Map[K, V]], isExpired: (Long, V) => Boolean)(now: Long): F[List[K]] = {
    ref.modify(
      m => {
        val l = scala.collection.mutable.ListBuffer.empty[K]
        m.foreach { case (k, item) =>
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

private[mules] case class PurgeableMapRef[F[_],K,V](mapRef: MapRef[F,K,V], purgeExpiredEntries: Long => F[List[K]])

private[mules] object PurgeableMapRef {
  def ofShardedImmutableMap[F[_]: Concurrent, K, V](
    shardCount: Int,
    isExpired: (Long, V) => Boolean
  ): F[PurgeableMapRef[F, K, Option[V]]] = {
    assert(shardCount >= 1, "MapRef.sharded should have at least 1 shard")
    val shards: F[List[Ref[F, Map[K, V]]]] =   List.fill(shardCount)(())
      .traverse(_ => Concurrent[F].ref[Map[K, V]](Map.empty))

    def purgeExpiredEntries(shards:List[Ref[F, Map[K, V]]])(now: Long) = shards.parFlatTraverse(SingleRef.purgeExpiredEntries(_, isExpired)(now))

    shards.map{ s =>
      PurgeableMapRef(
        fromSeqRefs(s),
        purgeExpiredEntries(s)
      )
    }
  }

  def ofConcurrentHashMap[F[_]: Async, K, V](
    initialCapacity: Int = 16,
    loadFactor: Float = 0.75f,
    concurrencyLevel: Int = 16,
    isExpired: (Long, V) => Boolean
  ): F[PurgeableMapRef[F, K, Option[V]]] = Concurrent[F].unit.map{ _ => //replaced Sync[F].delay.  Needed?
    val chm = new ConcurrentHashMap[K, V](initialCapacity, loadFactor, concurrencyLevel)
    val mapRef: MapRef[F, K, Option[V]] = MapRef.fromConcurrentHashMap(chm)
    val getKeys: () => F[List[K]] = () => Concurrent[F].unit.map{ _ =>
      val k = chm.keys()
      val builder = new mutable.ListBuffer[K]
      if (k != null){
        while (k.hasMoreElements()){
          val next = k.nextElement()
          builder.+=(next)
        }
      }
      builder.result()
    }
    def purgeExpiredEntries(now: Long): F[List[K]] = {
      val keys: F[List[K]] = getKeys()
      keys.flatMap(l =>
        l.flatTraverse(k =>
          mapRef(k).modify(optItem =>
            optItem.map(item =>
              if (isExpired(now, item))
                (None, List(k))
              else
                (optItem, List.empty)
            ).getOrElse((optItem, List.empty))
          )
        )
      )
    }
    PurgeableMapRef(mapRef, purgeExpiredEntries)
  }
}