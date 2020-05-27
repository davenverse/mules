package io.chrisdavenport.mules

import cats._
import cats.implicits._
import cats.effect._
import cats.effect.concurrent._

// Credit For the Idea and Approach to pchiusano
// Initial Idea - https://twitter.com/pchiusano/status/1260255494519865346
// Unison Impl - https://github.com/unisonweb/unison/blob/master/parser-typechecker/src/Unison/Util/Cache.hs
private class SemispaceCache[F[_]: Monad, K, V](
  gen0: Ref[F, Map[K, V]]
  , gen1: Ref[F, Map[K, V]]
  , maxSize: Int
) extends Cache[F, K, V]{

  private val fNoneV: F[Option[V]] = (None: Option[V]).pure[F]

  // As we atomically swap the Map for the empty map under conditions
  // then set, the race condition only exists if multiple times we reach the max size
  // from empty before the gen1.set completes. Very unlikely under normal conditions.
  def insert(k: K, v: V): F[Unit] = for {
    updateGen1 <- gen0.modify{ map => 
      val newMap = map + (k -> v)
      if (newMap.size > maxSize) (Map.empty[K,V], newMap.some)
      else (newMap, None)
    }
    out <- updateGen1.traverse_(gen1.set(_))
  } yield out
  
  def lookup(k: K): F[Option[V]] = gen0.get.flatMap(m0 => 
    m0.get(k) match {
      case s@Some(_) => (s: Option[V]).pure[F]
      case None => 
        gen1.get.flatMap(m1 => 
          m1.get(k) match {
            case None => fNoneV
            case s@Some(v) => insert(k, v).as(s) 
            // Small Race Condition: Chance of Placing a key into gen0 that was not present when gen1 = gen0 concurrently
          }
        )
    }
  )

  def delete(k: K): F[Unit] = for {
    _ <- gen1.update(m1 => m1 - k) // remove from gen1 first so subsequent copies won't override delete - NOT PERFECT
    out <- gen0.update(m0 => m0 - k) // Remove from gen0
  } yield out

}

object SemispaceCache {

  /**
   * Same as [[in]] but with both effects in the same F
   **/
  def of[F[_]: Sync, K, V](maxSize: Int): F[Cache[F, K, V]] = in[F, F, K, V](maxSize)

  /**
   * Create a cache of bounded size. Once the cache
   * reaches a size of `maxSize`, older unused entries
   * are evicted from the cache. Unlike LRU caching,
   * where cache hits require updating LRU info,
   * cache hits here are mostly read-only and contention free.
   * 
   *  Analogous to semispace GC, keep 2 maps: gen0 and gen1
   * `insert k v` is done in gen0
   * if full, gen1 = gen0; gen0 = Map.empty
   * `lookup k` is done in gen0; then gen1
   * if found in gen0, return immediately
   * if found in gen1, `insert k v`, then return
   * Thus, older keys not recently looked up are forgotten
   * 
   */
  def in[G[_]: Sync, F[_]: Sync, K, V](maxSize: Int): G[Cache[F, K, V]] = maxSize match {
    case lessThan0 if (lessThan0 <= 0) => NullCache.impl[F, K, V].pure[G]
    case ms => for {
      gen0 <- Ref.in[G, F, Map[K, V]](Map.empty[K, V])
      gen1 <- Ref.in[G, F, Map[K, V]](Map.empty[K, V])
    } yield new SemispaceCache[F, K, V](gen0, gen1, ms)
  }
}