# mules [![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.chrisdavenport/mules_2.13/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.chrisdavenport/mules_2.13)

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
import cats.effect.unsafe.implicits.global
```

Now Lets Use What We have built.

```scala
val getInserted = for {
  cache <- MemoryCache.ofSingleImmutableMap[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)))
  _     <- cache.insert("Foo", 1)
  value <- cache.lookup("Foo")
} yield value

getInserted.unsafeRunSync
// res0: Option[Int] = Some(1)

val getRemoved = for {
  cache <- MemoryCache.ofSingleImmutableMap[IO, String, Int](None)
  _     <- cache.insert("Foo", 1)
  _     <- cache.delete("Foo")
  value <- cache.lookup("Foo")
} yield value

getRemoved.unsafeRunSync
// res1: Option[Int] = None

val getAfterPurged = for {
  cache <- MemoryCache.ofSingleImmutableMap[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)))
  _     <- cache.insert("Foo", 1)
  _     <- IO.sleep(2.seconds)
  _     <- cache.purgeExpired
  value <- cache.lookupNoUpdate("Foo")
} yield value

getAfterPurged.unsafeRunSync
// res2: Option[Int] = None

val lookupAfterInterval = for {
  cache <- MemoryCache.ofSingleImmutableMap[IO, String, Int](Some(TimeSpec.unsafeFromDuration(1.second)))
  _     <- cache.insert("Foo", 1)
  _     <- IO.sleep(2.seconds)
  value <- cache.lookup("Foo")
} yield value

lookupAfterInterval.unsafeRunSync
// res3: Option[Int] = None
```
