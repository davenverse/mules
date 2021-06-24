val BuildScala = "2.13.6"
val ScalaTargets = Seq("2.12.13", BuildScala, "3.0.0")

ThisBuild / crossScalaVersions := ScalaTargets
ThisBuild / scalaVersion := BuildScala

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
  .settings(publish / skip := true)
  .settings(commonSettings)
  .aggregate(core, caffeine, reload, noop, bench)

lazy val bench = project.in(file("modules/bench"))
  .disablePlugins(MimaPlugin)
  .enablePlugins(JmhPlugin)
  .settings(publish / skip := true)
  .settings(commonSettings)
  .dependsOn(core, caffeine)

lazy val core = project.in(file("modules/core"))
  .settings(commonSettings)
  .settings(
    name := "mules",
    scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) => Seq("-Xsource:3")
      case _ => Nil
    })
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
    ),
    scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) => Seq("-Xsource:3")
      case _ => Nil
    })
  )

val catsV = "2.6.1"
val catsEffectV = "3.1.1"
val catsCollectionV = "0.9.3"

val specs2V = "4.11.0"
val disciplineSpecs2V = "1.1.6"

val kindProjectorV = "0.13.0"
val betterMonadicForV = "0.3.1"

lazy val commonSettings = Seq(
  scalaVersion := BuildScala, 
  crossScalaVersions := ScalaTargets,
  libraryDependencies ++= (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, _))=>
      Seq(
        compilerPlugin("org.typelevel" % "kind-projector" % kindProjectorV cross CrossVersion.full),
        compilerPlugin("com.olegpy"    %% "better-monadic-for" % betterMonadicForV)
      )
    case _ =>
      Nil
  }),
  libraryDependencies ++= Seq(
    "org.typelevel"               %% "cats-core"                  % catsV,
    "org.typelevel"               %% "cats-effect"                % catsEffectV,
    "io.chrisdavenport"           %% "mapref"                     % "0.2.0-M2",

    "org.typelevel"               %% "cats-effect-laws"           % catsEffectV   % Test,
    "org.typelevel"               %% "cats-effect-testing-specs2" % "1.1.1"       % Test,
    "org.scalameta"               %% "munit"                      % "0.7.26"      % Test,
    "org.scalameta"               %% "munit-scalacheck"           % "0.7.26"      % Test,
    "org.typelevel"               %% "munit-cats-effect-3"        % "1.0.5"       % Test,
    "org.typelevel"               %% "discipline-munit"           % "1.0.9"       % Test,
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
  )
))
