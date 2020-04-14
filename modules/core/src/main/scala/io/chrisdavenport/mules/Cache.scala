package io.chrisdavenport.mules

trait Lookup[F[_], K, V]{
  def lookup(k: K): F[Option[V]]
}

trait Get[F[_], K, V]{
  def get(k: K): F[V]
}

trait Insert[F[_], K, V]{
  def insert(k: K, v: V): F[Unit]
}

/** As [[Insert]] but allows setting an expiration time for inserts.
  *
  * The default implementation of [[#insert]] will use the
  * [[#defaultExpiration]] value, but concrete implementations ''may''
  * override this.
  */
trait LifetimeInsert[F[_], K, V] extends Insert[F, K, V]{

  /** The default expiration time for values inserted with the [[#insert]] method. */
  def defaultExpiration: Option[TimeSpec]

  /**
    * Insert an item in the cache, with an explicit expiration value.
    *
    * If the expiration value is None, the item will never expire. The default expiration value of the cache is ignored.
    *
    * The expiration value is relative to the current clockMonotonic time, i.e. it will be automatically added to the result of clockMonotonic for the supplied unit.
    **/
  def insertWithTimeout(optionTimeout: Option[TimeSpec])(k: K, v: V): F[Unit]

  override def insert(k: K, v: V): F[Unit] =
    this.insertWithTimeout(this.defaultExpiration)(k, v)
}

trait Delete[F[_], K]{
  def delete(k: K): F[Unit]
}

trait Cache[F[_], K, V]
  extends Lookup[F, K, V]
  with Insert[F, K, V]
  with Delete[F, K]

/** As [[Cache]] but additionally supports [[#insertWithTimeout]]. */
trait LifetimeCache[F[_], K, V]
    extends Cache[F, K, V]
    with LifetimeInsert[F, K, V]
