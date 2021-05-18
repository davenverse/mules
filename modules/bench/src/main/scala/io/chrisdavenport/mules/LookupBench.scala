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

  def testUnderContention(m: Cache[IO, Int, String], r: List[Int], w: List[Int])(implicit R: IORuntime) = {
    val set = w.traverse( m.insert(_, "foo"))
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

  def underContentionWaitReads(m: Cache[IO, Int, String], r: List[Int], w: List[Int])(implicit R: IORuntime) = {
    val set = w.traverse(m.insert(_, "foo"))
    val read = r.traverse(m.lookup(_))
    Concurrent[IO].bracket(set.start)(
      _ => read
    )(_.cancel).unsafeRunSync()
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

  def underContentionWaitWrites(m: Cache[IO, Int, String],r: List[Int], w: List[Int])(implicit R: IORuntime) = {
    val set = w.traverse( m.insert(_, "foo"))
    val read = r.traverse(m.lookup(_))
    Concurrent[IO].bracket(read.start)(
      _ => set
    )(_.cancel).unsafeRunSync()
  }

}

object LookUpBench {
  @State(Scope.Benchmark)
  class BenchStateRef {
    var memoryCache: MemoryCache[IO, Int, String] = _
    val writeList: List[Int] = (1 to 100).toList
    val readList : List[Int] = (1 to 100).toList
    implicit val R = IORuntime.global

    @Setup(Level.Trial)
    def setup(): Unit = {
      memoryCache = MemoryCache.ofSingleImmutableMap[IO, Int, String](None).unsafeRunSync()
    }

  }
  @State(Scope.Benchmark)
  class BenchStateCHM {
    var memoryCache: MemoryCache[IO, Int, String] = _
    val writeList: List[Int] = (1 to 100).toList
    val readList : List[Int] = (1 to 100).toList
    implicit val R = IORuntime.global

    @Setup(Level.Trial)
    def setup(): Unit = {
      memoryCache = MemoryCache.ofConcurrentHashMap[IO, Int, String](None).unsafeRunSync()
      memoryCache.insert(1, "yellow").unsafeRunSync()
    }

  }

  @State(Scope.Benchmark)
  class BenchStateCaffeine {
    var cache: Cache[IO, Int, String] = _
    val writeList: List[Int] = (1 to 100).toList
    val readList : List[Int] = (1 to 100).toList
    implicit val R = IORuntime.global

    @Setup(Level.Trial)
    def setup(): Unit = {
      cache = CaffeineCache.build[IO, Int, String](None, None, None).unsafeRunSync()
      cache.insert(1, "yellow").unsafeRunSync()
    }
  }
}
