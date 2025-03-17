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

package io.chrisdavenport.mules.noop

import io.chrisdavenport.mules.Cache
import cats.Applicative

private class NoOpCache[F[_], K, V](implicit F: Applicative[F]) extends Cache[F, K, V] {
  val noneF: F[Option[V]] = F.pure(None)
  // Members declared in io.chrisdavenport.mules.Delete
  def delete(k: K): F[Unit] = F.unit

  // Members declared in io.chrisdavenport.mules.Insert
  def insert(k: K, v: V): F[Unit] = F.unit

  // Members declared in io.chrisdavenport.mules.Lookup
  def lookup(k: K): F[Option[V]] = noneF
}

object NoOpCache {
  def impl[F[_]: Applicative, K, V]: Cache[F, K, V] = new NoOpCache[F, K, V]
}
