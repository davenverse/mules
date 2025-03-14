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

package io.chrisdavenport.mules.caffeine

import cats.implicits._
import io.chrisdavenport.mules.Cache
import com.github.benmanes.caffeine.cache.{Cache => CCache, Caffeine}
import cats.effect._
import io.chrisdavenport.mules.TimeSpec
import java.util.concurrent.TimeUnit

private class CaffeineCache[F[_], K, V](cc: CCache[K, V])(implicit sync: Sync[F])
    extends Cache[F, K, V] {
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
   * insertWithTimeout does not operate as the underlying cache is fully responsible for these
   * values
   */
  def build[F[_]: Sync, K, V](
      defaultTimeout: Option[TimeSpec],
      accessTimeout: Option[TimeSpec],
      maxSize: Option[Long]
  ): F[Cache[F, K, V]] = {
    Sync[F]
      .delay(Caffeine.newBuilder())
      .map(b => defaultTimeout.fold(b)(ts => b.expireAfterWrite(ts.nanos, TimeUnit.NANOSECONDS)))
      .map(b => accessTimeout.fold(b)(ts => b.expireAfterAccess(ts.nanos, TimeUnit.NANOSECONDS)))
      .map(b => maxSize.fold(b)(b.maximumSize))
      .map(_.build[K with Object, V with Object]())
      .map(_.asInstanceOf[CCache[K, V]]) // 2.12 hack
      .map(fromCache[F, K, V](_))
  }

  /** Build a Cache from a Caffeine Cache * */
  def fromCache[F[_]: Sync, K, V](cache: CCache[K, V]): Cache[F, K, V] =
    new CaffeineCache[F, K, V](cache)

}
