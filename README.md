# mules [![Build Status](https://travis-ci.com/ChristopherDavenport/mules.svg?branch=master)](https://travis-ci.com/ChristopherDavenport/mules)

Caches are tricky - Let Mules Haul its Weight.


## Quickstart

To use mules in an existing SBT project with Scala 2.11 or a later version, add the following dependencies to your build.sbt depending on your needs:

```scala
libraryDependencies ++= Seq(
  "io.chrisdavenport" %% "mules"     % "<version>"
)
```

Then the import

```scala
import io.chrisdavenport.mules._
import cats.effect.IO
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.global
// And Our Timer

implicit val timer = IO.timer(global)
```

Now Lets Use What We have built.

```scala
val getInserted = for {
        cache <- Cache.createCache[IO, String, Int](Some(Cache.TimeSpec.unsafeFromDuration(1.second)))
        _ <- cache.insert("Foo", 1)
        value <- cache.lookup("Foo")
} yield value
// getInserted: IO[Option[Int]] = Bind(
//   Map(
//     Delay(cats.effect.concurrent.Ref$$$Lambda$2068/1761796799@56bca113),
//     io.chrisdavenport.mules.Cache$$$Lambda$2069/1167171712@494e6dbb,
//     0
//   ),
//   ammonite.$sess.cmd7$$$Lambda$2070/1795982619@447d91ef
// )

getInserted.unsafeRunSync
// res0: Option[Int] = Some(1)

val getRemoved = for {
          cache <- Cache.createCache[IO, String, Int](None)
          _ <- cache.insert("Foo", 1)
          _ <- cache.delete("Foo")
          value <- cache.lookup("Foo")
  } yield value 
// getRemoved: IO[Option[Int]] = Bind(
//   Map(
//     Delay(cats.effect.concurrent.Ref$$$Lambda$2068/1761796799@63702e4),
//     io.chrisdavenport.mules.Cache$$$Lambda$2069/1167171712@4b0d7cb5,
//     0
//   ),
//   ammonite.$sess.cmd9$$$Lambda$2129/709004859@4c511912
// )

getRemoved.unsafeRunSync
// res1: Option[Int] = None


val getAfterPurged = for {
        cache <- Cache.createCache[IO, String, Int](Some(Cache.TimeSpec.unsafeFromDuration(1.second)))
        _ <- cache.insert("Foo", 1)
        _ <- timer.sleep(2.seconds)
        _ <- cache.purgeExpired
        value <- cache.lookupNoUpdate("Foo")
} yield value
// getAfterPurged: IO[Option[Int]] = Bind(
//   Map(
//     Delay(cats.effect.concurrent.Ref$$$Lambda$2068/1761796799@aa77fd0),
//     io.chrisdavenport.mules.Cache$$$Lambda$2069/1167171712@5bd3f1b3,
//     0
//   ),
//   ammonite.$sess.cmd13$$$Lambda$2170/787270911@6ce2f787
// )

getAfterPurged.unsafeRunSync
// res2: Option[Int] = None

val lookupAfterInterval = for {
        cache <- Cache.createCache[IO, String, Int](Some(Cache.TimeSpec.unsafeFromDuration(1.second)))
        _ <- cache.insert("Foo", 1)
        _ <- timer.sleep(2.seconds)
        value <- cache.lookup("Foo")
} yield value
// lookupAfterInterval: IO[Option[Int]] = Bind(
//   Map(
//     Delay(cats.effect.concurrent.Ref$$$Lambda$2068/1761796799@345fa41d),
//     io.chrisdavenport.mules.Cache$$$Lambda$2069/1167171712@6e5e4c90,
//     0
//   ),
//   ammonite.$sess.cmd15$$$Lambda$2183/1918145875@76c6c15
// )

lookupAfterInterval.unsafeRunSync
// res3: Option[Int] = None
```