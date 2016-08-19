import sbt.Keys._
import sbt._
import spray.revolver.RevolverPlugin.Revolver

object Build extends Build {

  import Dependencies._
  import Formatting._

  val scalaV = "2.11.8"
  val jvmV = "1.7"

  val commonScalacOptions = Seq(
    "-encoding", "utf8",
    s"-target:jvm-$jvmV",
    "-feature",
    "-language:implicitConversions",
    "-language:postfixOps",
    "-unchecked",
    "-deprecation",
    "-Xlog-reflective-calls"
  )

  lazy val basicSettings = Seq(
    organization  := "io.kagera",
    scalaVersion  := scalaV,
    scalacOptions := commonScalacOptions,
    incOptions    := incOptions.value.withNameHashing(true)
  )

  lazy val defaultProjectSettings = basicSettings ++ formattingSettings ++ Revolver.settings ++ INGRelease.publishSettings

  lazy val api = Project("api", file("api"))
    .settings(defaultProjectSettings: _*)
    .settings(
      name := "kagera-api",
      libraryDependencies ++= Seq(
        graph,
        shapeless,
        scalaReflect,
        scalatest % "test"))

  lazy val visualization = Project("visualization", file("visualization"))
    .dependsOn(api)
    .settings(defaultProjectSettings ++ Seq(
      name := "kagera-visualization",
      libraryDependencies ++= Seq(
        graph,
        graphDot)))

  lazy val akkaImplementation = Project("akka", file("akka"))
    .dependsOn(api)
    .settings(defaultProjectSettings ++ Seq(
      name      := "kagera-akka",
      mainClass := Some("io.kagera.akka.Main"),
      libraryDependencies ++= Seq(
        akkaActor,
        akkaPersistence,
        akkaSlf4j,
        akkaHttp,
        graph,
        akkaTestkit % "test",
        scalatest   % "test")
    ))

  lazy val root = Project("kagera", file(".")).aggregate(api, akkaImplementation, visualization).settings(defaultProjectSettings).settings(publish := { })
}
