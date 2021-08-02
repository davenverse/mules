val Scala213 = "2.13.5"

ThisBuild / crossScalaVersions := Seq("2.12.13", Scala213)
ThisBuild / scalaVersion := crossScalaVersions.value.last

ThisBuild / githubWorkflowArtifactUpload := false

ThisBuild / githubWorkflowBuild := Seq(
  WorkflowStep.Sbt(List("test", "mimaReportBinaryIssues")),
)

ThisBuild / githubWorkflowTargetTags ++= Seq("v*")

// currently only publishing tags
ThisBuild / githubWorkflowPublishTargetBranches :=
  Seq(RefPredicate.StartsWith(Ref.Tag("v")))

ThisBuild / githubWorkflowPublishPreamble +=
  WorkflowStep.Use(UseRef.Public("olafurpg", "setup-gpg", "v3"))

ThisBuild / githubWorkflowPublish := Seq(
  WorkflowStep.Sbt(
    List("ci-release"),
    name = Some("Publish artifacts to Sonatype"),
    env = Map(
      "PGP_PASSPHRASE" -> "${{ secrets.PGP_PASSPHRASE }}",
      "PGP_SECRET" -> "${{ secrets.PGP_SECRET }}",
      "SONATYPE_PASSWORD" -> "${{ secrets.SONATYPE_PASSWORD }}",
      "SONATYPE_USERNAME" -> "${{ secrets.SONATYPE_USERNAME }}"
    )
  ),
)

lazy val mules = project.in(file("."))
  .disablePlugins(MimaPlugin)
  .settings(skip in publish := true)
  .settings(commonSettings)
  .aggregate(core, caffeine, reload, noop, bench)

lazy val bench = project.in(file("modules/bench"))
  .disablePlugins(MimaPlugin)
  .enablePlugins(JmhPlugin)
  .settings(skip in publish := true)
  .settings(commonSettings)
  .dependsOn(core, caffeine)

lazy val core = project.in(file("modules/core"))
  .settings(commonSettings)
  .settings(
    name := "mules"
  )

lazy val caffeine = project.in(file("modules/caffeine"))
  .settings(commonSettings)
  .dependsOn(core)
  .settings(
    name := "mules-caffeine",
    libraryDependencies ++= Seq(
      "com.github.ben-manes.caffeine" % "caffeine" % "2.9.1"
    )
  )

lazy val noop = project.in(file("modules/noop"))
  .settings(commonSettings)
  .dependsOn(core)
  .settings(
    name := "mules-noop"
  )

lazy val reload = project.in(file("modules/reload"))
  .settings(commonSettings)
  .dependsOn(core)
  .settings(
    name := "mules-reload",
    libraryDependencies ++= Seq(
      "org.typelevel"               %% "cats-collections-core"      % catsCollectionV
    )
  )

val catsV = "2.6.1"
val catsEffectV = "3.1.0"
val catsCollectionV = "0.9.2"

val munitV = "0.7.25"
val munitCEV = "1.0.2"

lazy val commonSettings = Seq(
  scalaVersion := "2.13.5",
  crossScalaVersions := Seq(scalaVersion.value, "2.12.12"),

  addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.13.0" cross CrossVersion.full),
  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),

  testFrameworks += new TestFramework("munit.Framework"),

  libraryDependencies ++= Seq(
    "org.typelevel"               %% "cats-core"                  % catsV,
    "org.typelevel"               %% "cats-effect"                % catsEffectV,
    "io.chrisdavenport"           %% "mapref"                     % "0.2.0-M1",

    "org.typelevel"               %% "cats-effect-laws"           % catsEffectV   % Test,
    "org.scalameta"               %% "munit"                      % munitV        % Test,
    "org.scalameta"               %% "munit-scalacheck"           % munitV        % Test,
    "org.typelevel"               %% "munit-cats-effect-3"        % munitCEV      % Test,
  )
)

inThisBuild(List(
  organization := "io.chrisdavenport",
  homepage := Some(url("https://github.com/ChristopherDavenport/mules")),
  licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
  developers := List(
    Developer(
      "ChristopherDavenport",
      "Christopher Davenport",
      "chris@christopherdavenport.tech",
      url("https://github.com/ChristopherDavenport")
    )
  ),
  scalacOptions in (Compile, doc) ++= Seq(
      "-groups",
      "-sourcepath", (baseDirectory in LocalRootProject).value.getAbsolutePath,
      "-doc-source-url", "https://github.com/ChristopherDavenport/mules/blob/v" + version.value + "€{FILE_PATH}.scala"
  ),
))
