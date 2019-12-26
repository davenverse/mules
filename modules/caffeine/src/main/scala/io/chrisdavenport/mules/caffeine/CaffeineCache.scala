package io.chrisdavenport.mules.caffeine


import cats.implicits._
import io.chrisdavenport.mules.Cache
import com.github.benmanes.caffeine.cache.{Caffeine, Cache => CCache}
import cats.effect._
import io.chrisdavenport.mules.TimeSpec
import java.util.concurrent.TimeUnit

private class CaffeineCache[F[_], K, V](cc: CCache[K, V])(implicit sync: Sync[F]) extends Cache[F, K, V]{
  // Members declared in io.chrisdavenport.mules.Delete
  def delete(k: K): F[Unit] = sync.delay(cc.invalidate(k))
  
  // Members declared in io.chrisdavenport.mules.Insert
  def insert(k: K, v: V): F[Unit] = sync.delay(cc.put(k, v))
  
  // Members declared in io.chrisdavenport.mules.Lookup
  def lookup(k: K): F[Option[V]] = 
    sync.delay(Option(cc.getIfPresent(k)))

}


object CaffeineCache {

  /**
   * insertWithTimeout does not operate as the underlying cache is fully responsible for these values
   **/
  def build[F[_]: Sync, K, V](
    defaultTimeout: Option[TimeSpec],
    accessTimeout: Option[TimeSpec],
    maxSize: Option[Long]
  ): F[Cache[F, K, V]] = {
    Sync[F].delay(Caffeine.newBuilder())
      .map(b => defaultTimeout.fold(b)(ts => b.expireAfterWrite(ts.nanos, TimeUnit.NANOSECONDS)))
      .map(b => accessTimeout.fold(b)(ts => b.expireAfterAccess(ts.nanos, TimeUnit.NANOSECONDS)))
      .map(b => maxSize.fold(b)(b.maximumSize))
      .map(_.build[K with Object, V with Object]())
      .map(_.asInstanceOf[CCache[K, V]]) // 2.12 hack
      .map(fromCache[F, K, V](_))
  }

  /** Build a Cache from a Caffeine Cache **/
  def fromCache[F[_]: Sync, K, V](cache: CCache[K, V]): Cache[F, K, V] =
    new CaffeineCache[F, K, V](cache)


}