package io.chrisdavenport.mules.caffeine

import cats._
import cats.effect._
import com.github.benmanes.caffeine.cache.{Caffeine, Cache => CCache}
import io.chrisdavenport.mules._
import java.util.concurrent.TimeUnit

/** A Builder for a Caffeine based cache.
  *
  * The canonical method to create a [[CaffeineCacheBuilder]] is to invoke
  * [[CaffeineCacheBuilder#empty]].
  */
sealed trait CaffeineCacheBuilder[K, V] {
  /** Specifies a method to "weigh" values in the cache and a maximum weight for
    * the cache.
    *
    * @see [[https://www.javadoc.io/static/com.github.ben-manes.caffeine/caffeine/2.8.5/com/github/benmanes/caffeine/cache/Caffeine.html#weigher-com.github.benmanes.caffeine.cache.Weigher-]]
    * @see [[https://www.javadoc.io/static/com.github.ben-manes.caffeine/caffeine/2.8.5/com/github/benmanes/caffeine/cache/Caffeine.html#maximumWeight-long-]]
    */
  def withWeigher(weigher: Weigher[K, V]): CaffeineCacheBuilder[K, V]

  /** Specifies that each entry should be automatically removed from the cache
    * once a fixed duration has elapsed after the entry's creation, or the
    * most recent replacement of its value.
    *
    * @see [[https://www.javadoc.io/static/com.github.ben-manes.caffeine/caffeine/2.8.5/com/github/benmanes/caffeine/cache/Caffeine.html#expireAfterWrite-long-java.util.concurrent.TimeUnit-]]
    */
  def withWriteTimeout(timeout: TimeSpec): CaffeineCacheBuilder[K, V]

  /** Specifies that each entry should be automatically removed from the cache
    * once a fixed duration has elapsed after the entry's creation, the most
    * recent replacement of its value, or its last read.
    *
    * @see [[https://www.javadoc.io/static/com.github.ben-manes.caffeine/caffeine/2.8.5/com/github/benmanes/caffeine/cache/Caffeine.html#expireAfterAccess-long-java.util.concurrent.TimeUnit-]]
    */
  def withAccessTimeout(timeout: TimeSpec): CaffeineCacheBuilder[K, V]

  /** Specifies the maximum number of entries the cache may contain.
    *
    * @see [[https://www.javadoc.io/static/com.github.ben-manes.caffeine/caffeine/2.8.5/com/github/benmanes/caffeine/cache/Caffeine.html#maximumSize-long-]]
    */
  def withMaxSize(size: Long): CaffeineCacheBuilder[K, V]

  /** Build a cache from this builder.
    *
    * A convenience wrapper for invoking [[CaffeineCacheBuilder#buildCache_]].
    */
  final def buildCache_[F[_], G[_]](implicit F: ApplicativeError[F, Throwable], G: Sync[G]): F[Cache[G, K, V]] =
    CaffeineCacheBuilder.buildCache_[F, G, K, V](this)

  /** Build a cache from this builder.
    *
    * A convenience wrapper for invoking [[CaffeineCacheBuilder#buildCache]].
    */
  final def buildCache[F[_]: Sync]: F[Cache[F, K, V]] =
    buildCache_[F, F]
}

object CaffeineCacheBuilder {

  /** Must be hidden to prevent binary compat issues, but we use a case class to
    * back the [[CaffeineCacheBuilder]] so we can lean on it's `copy` method.
    */
  private[this] case class CaffeineCacheConfig[K,V](
    weigher: Option[Weigher[K, V]],
    writeTimeout: Option[TimeSpec],
    accessTimeout: Option[TimeSpec],
    maxSize: Option[Long]
  ) extends CaffeineCacheBuilder[K, V] {
    override final def withWeigher(weigher: Weigher[K, V]): CaffeineCacheBuilder[K, V] =
      copy(weigher = Some(weigher))
    override final def withWriteTimeout(timeout: TimeSpec): CaffeineCacheBuilder[K, V] =
      copy(writeTimeout = Some(timeout))
    override final def withAccessTimeout(timeout: TimeSpec): CaffeineCacheBuilder[K, V] =
      copy(accessTimeout = Some(timeout))
    override final def withMaxSize(size: Long): CaffeineCacheBuilder[K, V] =
      copy(maxSize = Some(size))
  }

  private[this] object CaffeineCacheConfig {
    def empty[K, V]: CaffeineCacheConfig[K, V] =
      CaffeineCacheConfig[K, V](None, None, None, None)
  }

  /** Create a new empty [[CaffeineCacheBuilder]] */
  def empty[K, V]: CaffeineCacheBuilder[K, V] =
    (CaffeineCacheConfig.empty[K, V]: CaffeineCacheBuilder[K, V])

  /** Given a [[CaffeineCacheBuilder]] create a new Mules `Cache`. */
  def buildCache_[F[_], G[_], K, V](
    builder: CaffeineCacheBuilder[K, V]
  )(implicit F: ApplicativeError[F, Throwable], G: Sync[G]): F[Cache[G, K, V]] = builder match {
    case builder: CaffeineCacheConfig[K, V] =>
      type C = Caffeine[K, V]
      val jBuilder: C =
        Caffeine.newBuilder.asInstanceOf[Caffeine[K, V]]
      F.catchNonFatal(
        ((CaffeineCache.fromCache[G, K, V](_)) compose
         ((jb: C) => jb.build.asInstanceOf[CCache[K, V]]) compose
         ((jb: C) => builder.weigher.fold(jb)(w => jb.weigher(w.asJavaWeigher).maximumWeight(w.maximumWeight))) compose
         ((jb: C) => builder.writeTimeout.fold(jb)(ts => jb.expireAfterWrite(ts.nanos, TimeUnit.NANOSECONDS))) compose
         ((jb: C) => builder.accessTimeout.fold(jb)(ts => jb.expireAfterAccess(ts.nanos, TimeUnit.NANOSECONDS))) compose
         ((jb: C) => builder.maxSize.fold(jb)(jb.maximumSize))
        )(jBuilder)
      )
  }

  /** Given a [[CaffeineCacheBuilder]] create a new Mules `Cache`. */
  def buildCache[F[_]: Sync, K, V](
    builder: CaffeineCacheBuilder[K, V]
  ): F[Cache[F, K, V]] =
    buildCache_[F, F, K, V](builder)
}
