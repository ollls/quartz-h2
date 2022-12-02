import Dependencies._

ThisBuild / scalaVersion := "3.2.1"
ThisBuild / version := "0.2.0-SNAPSHOT"
ThisBuild / organization := "io.quartz"
ThisBuild / organizationName := "quartz"

Runtime / unmanagedClasspath += baseDirectory.value / "src" / "main" / "resources"

lazy val root = (project in file("."))
  .settings(
    name := "quartz-h2",
    libraryDependencies += scalaTest % Test,
    libraryDependencies += "org.typelevel" %% "cats-effect" % "3.3.11",
    libraryDependencies += "org.typelevel" %% "log4cats-slf4j" % "2.4.0", // Direct Slf4j Support - Recommended
    libraryDependencies += "co.fs2" %% "fs2-core" % "3.2.7",
    libraryDependencies += "co.fs2" %% "fs2-io" % "3.2.7",
    libraryDependencies += "com.twitter" % "hpack" % "1.0.2",
    libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.3.5",
    libraryDependencies += "org.slf4j" % "slf4j-api" % "2.0.4"
  )


  lazy val RIO = (project in file("examples/RIO"))
  .settings(
    name := "example",
    libraryDependencies += "org.typelevel" %% "cats-effect" % "3.3.11",
    libraryDependencies += "io.quartz" %% "quartz-h2" % "0.2.0-SNAPSHOT"
  )

    lazy val IO = (project in file("examples/IO"))
  .settings(
    name := "example",
    libraryDependencies += "org.typelevel" %% "cats-effect" % "3.3.11",
    libraryDependencies += "io.quartz" %% "quartz-h2" % "0.2.0-SNAPSHOT"
  )

  scalacOptions ++= Seq(
  //"-Wunused:imports",
  //"-Xfatal-warnings",
  "-deprecation", 
  "-feature",
)

// See https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html for instructions on how to publish to Sonatype.
