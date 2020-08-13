package io.chrisdavenport.mules.caffeine

import io.chrisdavenport.mules.Cache
import com.github.benmanes.caffeine.cache.{Cache => CCache}
import cats.effect._
import io.chrisdavenport.mules.TimeSpec

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
  @deprecated(message = "Please use CaffeineCacheBuilder.buildCache or CaffeineCacheBuilder.buildCache_ instead", since = "0.5.0")
  def build[F[_]: Sync, K, V](
    defaultTimeout: Option[TimeSpec],
    accessTimeout: Option[TimeSpec],
    maxSize: Option[Long]
  ): F[Cache[F, K, V]] = {
    type C = CaffeineCacheBuilder[K, V]
    val builder: C = CaffeineCacheBuilder.empty[K, V]

    (((builder: C) => CaffeineCacheBuilder.buildCache[F, K, V](builder)) compose
     ((builder: C) => defaultTimeout.fold(builder)(ts => builder.withWriteTimeout(ts))) compose
     ((builder: C) => accessTimeout.fold(builder)(ts => builder.withAccessTimeout(ts))) compose
     ((builder: C) => maxSize.fold(builder)(s => builder.withMaxSize(s)))
    )(builder)
  }

  /** Build a Cache from a Caffeine Cache **/
  def fromCache[F[_]: Sync, K, V](cache: CCache[K, V]): Cache[F, K, V] =
    new CaffeineCache[F, K, V](cache)
}
