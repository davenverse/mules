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

package io.chrisdavenport.mules

// import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations._

import cats.implicits._
import cats.effect._
import cats.effect.unsafe.IORuntime
import io.chrisdavenport.mules.caffeine.CaffeineCache

@BenchmarkMode(Array(Mode.Throughput))
// @OutputTimeUnit(TimeUnit.MILLISECONDS)
class LookUpBench {
  import LookUpBench._

  @Benchmark
  def contentionSingleImmutableMap(in: BenchStateRef) =
    testUnderContention(in.memoryCache, in.readList, in.writeList)(in.R)

  @Benchmark
  def contentionConcurrentHashMap(in: BenchStateCHM) =
    testUnderContention(in.memoryCache, in.readList, in.writeList)(in.R)

  @Benchmark
  def contentionCaffeine(in: BenchStateCaffeine) =
    testUnderContention(in.cache, in.readList, in.writeList)(in.R)

  def testUnderContention(m: Cache[IO, Int, String], r: List[Int], w: List[Int])(implicit
      R: IORuntime
  ) = {
    val set = w.traverse(m.insert(_, "foo"))
    val read = r.traverse(m.lookup(_))
    val action = (set, read).parMapN((_, _) => ())
    action.unsafeRunSync()
  }

  @Benchmark
  def contentionReadsSingleImmutableMap(in: BenchStateRef) =
    underContentionWaitReads(in.memoryCache, in.readList, in.writeList)(in.R)

  @Benchmark
  def contentionReadsConcurrentHashMap(in: BenchStateCHM) =
    underContentionWaitReads(in.memoryCache, in.readList, in.writeList)(in.R)

  @Benchmark
  def contentionReadsCaffeine(in: BenchStateCaffeine) =
    underContentionWaitReads(in.cache, in.readList, in.writeList)(in.R)

  def underContentionWaitReads(m: Cache[IO, Int, String], r: List[Int], w: List[Int])(implicit
      R: IORuntime
  ) = {
    val set = w.traverse(m.insert(_, "foo"))
    val read = r.traverse(m.lookup(_))
    Concurrent[IO].bracket(set.start)(_ => read)(_.cancel).unsafeRunSync()
  }

  @Benchmark
  def contentionWritesSingleImmutableMap(in: BenchStateRef) =
    underContentionWaitWrites(in.memoryCache, in.readList, in.writeList)(in.R)

  @Benchmark
  def contentionWritesConcurrentHashMap(in: BenchStateCHM) =
    underContentionWaitWrites(in.memoryCache, in.readList, in.writeList)(in.R)

  @Benchmark
  def contentionWritesCaffeine(in: BenchStateCaffeine) =
    underContentionWaitWrites(in.cache, in.readList, in.writeList)(in.R)

  def underContentionWaitWrites(m: Cache[IO, Int, String], r: List[Int], w: List[Int])(implicit
      R: IORuntime
  ) = {
    val set = w.traverse(m.insert(_, "foo"))
    val read = r.traverse(m.lookup(_))
    Concurrent[IO].bracket(read.start)(_ => set)(_.cancel).unsafeRunSync()
  }

}

object LookUpBench {
  @State(Scope.Benchmark)
  class BenchStateRef {
    var memoryCache: MemoryCache[IO, Int, String] = _
    val writeList: List[Int] = (1 to 100).toList
    val readList: List[Int] = (1 to 100).toList
    implicit val R: IORuntime = IORuntime.global

    @Setup(Level.Trial)
    def setup(): Unit =
      memoryCache = MemoryCache.ofSingleImmutableMap[IO, Int, String](None).unsafeRunSync()(R)

  }
  @State(Scope.Benchmark)
  class BenchStateCHM {
    var memoryCache: MemoryCache[IO, Int, String] = _
    val writeList: List[Int] = (1 to 100).toList
    val readList: List[Int] = (1 to 100).toList
    implicit val R: IORuntime = IORuntime.global

    @Setup(Level.Trial)
    def setup(): Unit = {
      memoryCache = MemoryCache.ofConcurrentHashMap[IO, Int, String](None).unsafeRunSync()(R)
      memoryCache.insert(1, "yellow").unsafeRunSync()(R)
    }

  }

  @State(Scope.Benchmark)
  class BenchStateCaffeine {

    var cache: Cache[IO, Int, String] = _
    val writeList: List[Int] = (1 to 100).toList
    val readList: List[Int] = (1 to 100).toList
    implicit val R: IORuntime = IORuntime.global

    @Setup(Level.Trial)
    def setup(): Unit = {
      cache = CaffeineCache.build[IO, Int, String](None, None, None).unsafeRunSync()(R)
      cache.insert(1, "yellow").unsafeRunSync()(R)
    }
  }
}
