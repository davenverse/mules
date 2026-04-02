ThisBuild / tlBaseVersion := "2.2"
ThisBuild / organization := "io.chrisdavenport"
ThisBuild / organizationName := "Christopher Davenport"
ThisBuild / startYear := Some(2018)
ThisBuild / licenses := Seq(License.MIT)
ThisBuild / developers := List(
  tlGitHubDev("christopherdavenport", "Christopher Davenport")
)
ThisBuild / tlCiReleaseBranches := Seq("main")
ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"

ThisBuild / crossScalaVersions := Seq("2.12.21", "2.13.18", "3.3.7")
ThisBuild / scalaVersion := "3.3.7"

ThisBuild / testFrameworks += new TestFramework("munit.Framework")

ThisBuild / versionScheme := Some("early-semver")

ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("11"))

val catsV = "2.13.0"
val catsEffectV = "3.7.0"
val catsCollectionV = "0.9.10"

val munitV = "1.2.4"
val munitCEV = "2.2.0"
val munitScalacheckV = "1.2.0" 

lazy val mules = tlCrossRootProject
  .aggregate(core, caffeine, reload, noop, bench)

lazy val bench = project
  .in(file("modules/bench"))
  .enablePlugins(JmhPlugin)
  .disablePlugins(MimaPlugin)
  .enablePlugins(NoPublishPlugin)
  .dependsOn(core.jvm, caffeine)

lazy val core = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/core"))
  .settings(
    name := "mules",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-core"   % catsV,
      "org.typelevel" %%% "cats-effect" % catsEffectV
    ),
    tlJdkRelease := Some(8)
  )
  .settings(testDeps)
  .jsSettings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
  )

lazy val caffeine = project
  .in(file("modules/caffeine"))
  .dependsOn(core.jvm)
  .settings(
    name := "mules-caffeine",
    libraryDependencies ++= Seq(
      "com.github.ben-manes.caffeine" % "caffeine" % "3.1.6"
    )
  )
  .settings(testDeps)

lazy val noop = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/noop"))
  .dependsOn(core)
  .settings(
    name := "mules-noop",
    tlJdkRelease := Some(8)
  )
  .settings(testDeps)
  .jsSettings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
  )

lazy val reload = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/reload"))
  .dependsOn(core)
  .settings(
    name := "mules-reload",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-collections-core" % catsCollectionV
    ),
    tlJdkRelease := Some(8)
  )
  .settings(testDeps)
  .jsSettings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
  )

lazy val testDeps = Seq(
  libraryDependencies ++= Seq(
    "org.typelevel" %%% "cats-effect-laws"  % catsEffectV % Test,
    "org.scalameta" %%% "munit"             % munitV      % Test,
    "org.scalameta" %%% "munit-scalacheck"  % munitScalacheckV      % Test,
    "org.typelevel" %%% "munit-cats-effect" % munitCEV    % Test
  )
)
