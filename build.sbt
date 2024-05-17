ThisBuild / tlBaseVersion := "0.7"
ThisBuild / organization := "io.chrisdavenport"
ThisBuild / organizationName := "Christopher Davenport"
ThisBuild / licenses := Seq(License.MIT)
ThisBuild / developers := List(
  tlGitHubDev("christopherdavenport", "Christopher Davenport")
)
ThisBuild / tlCiReleaseBranches := Seq("main")
ThisBuild / tlSonatypeUseLegacyHost := true

ThisBuild / crossScalaVersions := Seq("2.12.19", "2.13.8", "3.2.2")
ThisBuild / scalaVersion := "3.2.2"

ThisBuild / testFrameworks += new TestFramework("munit.Framework")

ThisBuild / versionScheme := Some("early-semver")

ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("11"))


val catsV = "2.9.0"
val catsEffectV = "3.4.9"
val catsCollectionV = "0.9.6"

val munitV = "1.0.0-M7"
val munitCEV = "2.0.0-M3"

lazy val mules = tlCrossRootProject
  .aggregate(core, caffeine, reload, noop, bench)

lazy val bench = project.in(file("modules/bench"))
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
      "org.typelevel"               %%% "cats-core"                  % catsV,
      "org.typelevel"               %%% "cats-effect"                % catsEffectV,
    ),
    tlJdkRelease := Some(8)
  ).settings(testDeps)
  .jsSettings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule)},
  )

lazy val caffeine = project.in(file("modules/caffeine"))
  .dependsOn(core.jvm)
  .settings(
    name := "mules-caffeine",
    libraryDependencies ++= Seq(
      "com.github.ben-manes.caffeine" % "caffeine" % "3.1.6"
    ),
  ).settings(testDeps)

lazy val noop = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/noop"))
  .dependsOn(core)
  .settings(
    name := "mules-noop",
    tlJdkRelease := Some(8)
  ).settings(testDeps)
  .jsSettings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule)},
  )

lazy val reload = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/reload"))
  .dependsOn(core)
  .settings(
    name := "mules-reload",
    libraryDependencies ++= Seq(
      "org.typelevel"               %%% "cats-collections-core"      % catsCollectionV
    ),
    tlJdkRelease := Some(8),
  ).settings(testDeps)
  .jsSettings(
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule)},
  )

lazy val testDeps = Seq(
  libraryDependencies ++= Seq(
    "org.typelevel" %%% "cats-effect-laws" % catsEffectV % Test,
    "org.scalameta" %%% "munit" % munitV % Test,
    "org.scalameta" %%% "munit-scalacheck" % munitV % Test,
    "org.typelevel" %%% "munit-cats-effect" % munitCEV % Test,
  )
)
