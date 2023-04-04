import Dependencies._

ThisBuild / scalaVersion := "3.2.1"
ThisBuild / version := "0.4.5"
ThisBuild / organization := "io.github.ollls"
ThisBuild / organizationName := "ollls"
ThisBuild / versionScheme := Some("strict")
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
    libraryDependencies += "org.typelevel" %% "cats-effect" % "3.4.5" % "provided",
    libraryDependencies += "org.typelevel" %% "log4cats-slf4j" % "2.4.0", // Direct Slf4j Support - Recommended
    libraryDependencies += "co.fs2" %% "fs2-core" % "3.4.0",
    libraryDependencies += "co.fs2" %% "fs2-io" % "3.4.0",
    libraryDependencies += "com.twitter" % "hpack" % "1.0.2",
    libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.3.5",
    libraryDependencies += "org.slf4j" % "slf4j-api" % "2.0.4"
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

scalacOptions ++= Seq(
  // "-Wunused:imports",
  // "-Xfatal-warnings",
  "-deprecation",
  "-feature"
)

// See https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html for instructions on how to publish to Sonatype.
