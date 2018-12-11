package io.chrisdavenport.mules.reload

import cats.collections.Dequeue

case class BoundedQueue[A](maxSize: Int, currentSize: Int, queue: Dequeue[A]) {

  //push a and return the popped value if maxSize has been reached
  def push(a : A): (BoundedQueue[A], Option[A]) = BoundedQueue.push(this, a)
}


object BoundedQueue {

  def empty[A](maxSize : Int) : BoundedQueue[A] = BoundedQueue(maxSize, 0, Dequeue.empty)

  def push[A](boundedQueue: BoundedQueue[A], a : A): (BoundedQueue[A], Option[A]) = {
    import boundedQueue._
    if((currentSize + 1) > maxSize)
      queue.unsnoc match {
        case None =>
          (BoundedQueue(maxSize, 1, queue.cons(a)), None)
        case Some((out, q)) => (BoundedQueue(maxSize, currentSize, q.cons(a)), Some(out))
      }
    else (BoundedQueue(maxSize, currentSize + 1, queue.cons(a)), None)
  }

}
