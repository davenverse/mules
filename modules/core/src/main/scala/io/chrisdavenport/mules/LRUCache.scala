package io.chrisdavenport.mules

// import cats._
import cats.effect._
import cats.effect.concurrent.{Ref, Semaphore}
import cats.effect.implicits._
import cats.implicits._
import scala.concurrent.duration._
import scala.collection.immutable.Map

import io.chrisdavenport.mapref.MapRef
// import io.chrisdavenport.mapref.implicits._

import java.util.concurrent.ConcurrentHashMap

final class LRUCache[F[_], K, V] private (
  lock: Semaphore[F],
  queue: scala.collection.mutable.Queue[K],
  val maxSize: Int,
  private val mapRef: MapRef[F, K, Option[LRUCache.LRUCacheItem[V]]],
  private val purgeExpiredEntriesOpt : Option[Long => F[List[K]]], // Optional Performance Improvement over Default
  val defaultExpiration: Option[TimeSpec],
)(implicit sync: Sync[F], C: Clock[F]) extends Cache[F, K, V]{
  import LRUCache.LRUCacheItem

  private val noneF:F[None.type] = sync.pure(None)
  private def noneFA[A]: F[Option[A]] = noneF.widen[Option[A]]

  private def purgeExpiredEntriesDefault(now: Long): F[List[K]] = {
    mapRef.keys.flatMap(l => 
      l.flatTraverse(k => 
        mapRef(k).modify(optItem => 
          optItem.map(item => 
            if (LRUCache.isExpired(now, item)) 
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

    /**
   * Delete all items that are expired.
   *
   * This is one big atomic operation.
   **/
  def purgeExpired: F[Unit] = {
    lockResource >> {
      for {
        now <- Resource.liftF(C.monotonic(NANOSECONDS))
        out <- Resource.make(purgeExpiredEntries(now))(out => sync.delay{queue.subtractAll(out); ()} )
      } yield out
    }
  }.use(_ => sync.unit)

  private val lockResource = Resource.make(lock.acquire)(_ => lock.release)

    // Members declared in io.chrisdavenport.mules.Delete
    def delete(k: K): F[Unit] = {
      lockResource >>
      Resource.make(
        mapRef(k).modify{
          case None => 
          (None, sync.unit)
          case Some(_) => 
            (None, sync.delay{queue.subtractOne(k); ()})
        }
      )(identity)
    }.use(_ => sync.unit)
  
    // Members declared in io.chrisdavenport.mules.Insert
    def insert(k: K, v: V): F[Unit] = 
      insertWithTimeout(defaultExpiration)(k, v)

    def insertWithTimeout(optionTimeout: Option[TimeSpec])(k: K, v: V): F[Unit] = {
      lockResource >> {
        for {
          now <- Resource.liftF(C.monotonic(NANOSECONDS))
          timeout = optionTimeout.map(ts => TimeSpec.unsafeFromNanos(now + ts.nanos))
          out <- Resource.make{
            mapRef(k).modify{
              case None =>
                def insertEmpty: F[Unit] = sync.suspend{
                  if (queue.size >= maxSize) {
                    queue.enqueue(k)
                    val head = queue.dequeue()
                    mapRef(head).set(None)
                  } else {
                    queue.enqueue(k)
                    sync.unit
                  }
                }
                (LRUCacheItem[V](v, timeout).some, insertEmpty)
              case Some(_) => (LRUCacheItem[V](v, timeout).some, sync.delay{queue.subtractOne(k); queue.enqueue(k); ()})
            }
          }(identity)
        } yield out
      }
    }.use(_ => sync.unit)
    
    // Members declared in io.chrisdavenport.mules.Lookup
    def lookup(k: K): F[Option[V]] = lockResource.use{_ => 
      for {
        now <- C.monotonic(NANOSECONDS)
        out <- 
          mapRef(k).modify[F[Option[LRUCacheItem[V]]]]{
            case s@Some(value) => 
              if (LRUCache.isExpired(now, value)){
                (None, sync.delay(queue.subtractOne(k)).as(None))
              } else {
                (s, sync.delay{queue.subtractOne(k); queue.enqueue(k); ()} >> sync.pure(s))
              }
            case None => 
              (None, noneFA)
          }.bracketCase{
            a => a.map(_.map(_.item))
          }{
            case (_, ExitCase.Completed) => sync.unit
            case (action, ExitCase.Canceled) => action.void
            case (action, ExitCase.Error(_)) => action.void
          }
      } yield  out
    }
}

object LRUCache {
  private case class LRUCacheItem[A](
    item: A,
    itemExpiration: Option[TimeSpec]
  )
  private def isExpired[A](checkAgainst: Long, cacheItem: LRUCacheItem[A]): Boolean = {
    cacheItem.itemExpiration match{ 
      case Some(e) if e.nanos < checkAgainst => true
      case _ => false
    }
  }

    /**
   *
   * Initiates a background process that checks for expirations every certain amount of time.
   *
   * @param memoryCache: The cache to check and expire automatically.
   * @param checkOnExpirationsEvery: How often the expiration process should check for expired keys.
   *
   * @return an `Resource[F, Unit]` that will keep removing expired entries in the background.
   **/
  def liftToAuto[F[_]: Concurrent: Timer, K, V](
    memoryCache: MemoryCache[F, K, V],
    checkOnExpirationsEvery: TimeSpec
  ): Resource[F, Unit] = {
    def runExpiration(cache: MemoryCache[F, K, V]): F[Unit] = {
      val check = TimeSpec.toDuration(checkOnExpirationsEvery)
      Timer[F].sleep(check) >> cache.purgeExpired >> runExpiration(cache)
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
  def ofSingleImmutableMap[F[_]: Concurrent: Clock, K, V](
    maxSize: Int,
    defaultExpiration: Option[TimeSpec]
  ): F[LRUCache[F, K, V]] = 
    Ref.of[F, Map[K, LRUCacheItem[V]]](Map.empty[K, LRUCacheItem[V]])
      .flatMap{ref  =>
        Semaphore[F](1).map{sem => 
          new LRUCache[F, K, V](
            sem,
            scala.collection.mutable.Queue.empty[K],
            maxSize,
            MapRef.fromSingleImmutableMapRef(ref),
            {l: Long => SingleRef.purgeExpiredEntries(ref)(l)}.some,
            defaultExpiration,
          )
        }
      }

  def ofShardedImmutableMap[F[_]: Concurrent : Clock, K, V](
    maxSize: Int,
    shardCount: Int,
    defaultExpiration: Option[TimeSpec]
  ): F[LRUCache[F, K, V]] = 
    MapRef.ofShardedImmutableMap[F, K, LRUCacheItem[V]](shardCount).flatMap{mr => 
      ofMapRef[F, K, V](
      mr,
      maxSize,
      defaultExpiration,
      ) 
    }

  def ofConcurrentHashMap[F[_]: Concurrent: Clock, K, V](
    maxSize: Int,
    defaultExpiration: Option[TimeSpec],
    initialCapacity: Int = 16,
    loadFactor: Float = 0.75f,
    concurrencyLevel: Int = 16,
  ): F[LRUCache[F, K, V]] = Sync[F].suspend{
    val chm = new ConcurrentHashMap[K, LRUCacheItem[V]](initialCapacity, loadFactor, concurrencyLevel)
    ofMapRef[F, K, V](
      MapRef.fromConcurrentHashMap(chm),
      maxSize,
      defaultExpiration,
    )
  }

  def ofMapRef[F[_]: Concurrent: Clock, K, V](
    mr: MapRef[F, K, Option[LRUCacheItem[V]]],
    maxSize: Int,
    defaultExpiration: Option[TimeSpec]
  ): F[LRUCache[F, K, V]] = Semaphore[F](1).map{sem => 
    new LRUCache[F, K, V](
      sem,
      scala.collection.mutable.Queue.empty[K],
      maxSize,
      mr,
      None,
      defaultExpiration,
    )
  }


  private object SingleRef {

    def purgeExpiredEntries[F[_], K, V](ref: Ref[F, Map[K, LRUCacheItem[V]]])(now: Long): F[List[K]] = {
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

}