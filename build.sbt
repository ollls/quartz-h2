import Dependencies._

ThisBuild / scalaVersion := "3.3.3"
ThisBuild / version := "0.7"
ThisBuild / organization := "io.github.ollls"
ThisBuild / organizationName := "ollls"
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / developers := List(
  Developer(
    id = "ostrygun",
    name = "Oleg Strygun",
    email = "ostrygun@gmail.com",
    url = url("https://github.com/ollls/")
  )
)

ThisBuild / licenses := List("Apache 2" -> new URL("http://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / homepage := Some(url("https://github.com/ollls/quartz-h2"))
ThisBuild / credentials += Credentials(Path.userHome / ".sbt" / ".credentials")
ThisBuild / credentials += Credentials(
  "GnuPG Key ID",
  "gpg",
  "F85809244447DB9FA35A3C9B1EB44A5FC60F4104", // key identifier
  "ignored" // this field is ignored; passwords are supplied by pinentry
)

ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / publishTo := {
  val nexus = "https://s01.oss.sonatype.org/"
  if (isSnapshot.value) Some("snapshots" at nexus + "content/repositories/snapshots")
  else Some("releases" at nexus + "service/local/staging/deploy/maven2")
}
ThisBuild / publishMavenStyle := true

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/ollls/quartz-h2"),
    "scm:git@github.com:ollls/quartz-h2"
  )
)

Runtime / unmanagedClasspath += baseDirectory.value / "src" / "main" / "resources"

lazy val root = (project in file("."))
  .settings(
    organization := "io.github.ollls",
    name := "quartz-h2",
    libraryDependencies += scalaTest % Test,
    libraryDependencies += "org.typelevel" %% "cats-effect" % "3.5.4",
    libraryDependencies += "org.typelevel" %% "log4cats-slf4j" % "2.4.0", // Direct Slf4j Support - Recommended
    libraryDependencies += "co.fs2" %% "fs2-core" % "3.10.2",
    libraryDependencies += "co.fs2" %% "fs2-io" % "3.10.2",
    libraryDependencies += "com.twitter" % "hpack" % "1.0.2",
    libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.5.3",
    libraryDependencies += "org.slf4j" % "slf4j-api" % "2.0.12",
    libraryDependencies += "org.typelevel" %% "cats-effect-testing-minitest" % "1.5.0" % Test
  )

lazy val RIO = (project in file("examples/RIO"))
  .dependsOn(root)
  .settings(
    name := "example"
  )

lazy val IO = (project in file("examples/IO"))
  .dependsOn(root)
  .settings(
    name := "example"
  )

  lazy val TAPIR = (project in file("examples/STTP"))
  .dependsOn(root)
  .dependsOn(TAPIR_ROUTER)
  .settings(
    name := "example",
    libraryDependencies ++= Seq(
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % "2.19.1",
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.19.1" % "compile-internal"
    )

  )

lazy val TAPIR_ROUTER = (project in file("tapir-quartz-h2"))
  .dependsOn(root)
  .settings(
    organization := "io.github.ollls",
    name := "tapir-quartz-h2",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir" %% "tapir-core" % "1.10.5",
      "com.softwaremill.sttp.tapir" %% "tapir-jsoniter-scala" % "1.10.5",
      "com.softwaremill.sttp.tapir" %% "tapir-server" % "1.10.5",
      "com.softwaremill.sttp.tapir" %% "tapir-cats-effect" % "1.10.5",
    )
  )

scalacOptions ++= Seq(
  "-deprecation",
  "-feature"
)
testFrameworks += new TestFramework("minitest.runner.Framework")

// See https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html for instructions on how to publish to Sonatype.
