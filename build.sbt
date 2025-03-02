import java.net.URI
import Dependencies._
import scala.sys.process._

ThisBuild / scalaVersion := "3.3.3"
ThisBuild / version := "0.8.5"
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

ThisBuild / licenses := List("Apache 2" -> new URI("http://www.apache.org/licenses/LICENSE-2.0.txt").toURL)
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
  .dependsOn(IOURING_LIB)
  .settings(
    organization := "io.github.ollls",
    name := "quartz-h2",
    libraryDependencies += scalaTest % Test,
    libraryDependencies += "org.typelevel" %% "cats-effect" % "3.5.4",
    libraryDependencies += "org.typelevel" %% "log4cats-slf4j" % "2.7.0", // Direct Slf4j Support - Recommended
    libraryDependencies += "co.fs2" %% "fs2-core" % "3.10.2",
    libraryDependencies += "co.fs2" %% "fs2-io" % "3.10.2",
    libraryDependencies += "com.twitter" % "hpack" % "1.0.2",
    libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.5.3",
    libraryDependencies += "org.slf4j" % "slf4j-api" % "2.0.12",
    libraryDependencies += "org.typelevel" %% "cats-effect-testing-minitest" % "1.5.0" % Test,
    libraryDependencies += "org.eclipse.collections" % "eclipse-collections-api" % "11.1.0",
    libraryDependencies += "org.eclipse.collections" % "eclipse-collections" % "11.1.0"
    // libraryDependencies += "sh.blake.niouring" % "nio_uring" % "0.1.4"
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
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.19.1" % "compile-internal",
      "com.softwaremill.sttp.tapir" %% "tapir-jsoniter-scala" % "1.10.5"
    )
  )

lazy val TAPIR_ROUTER = (project in file("tapir-quartz-h2"))
  .dependsOn(root)
  .settings(
    organization := "io.github.ollls",
    name := "tapir-quartz-h2",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir" %% "tapir-core" % "1.10.5",
      "com.softwaremill.sttp.tapir" %% "tapir-server" % "1.10.5",
      "com.softwaremill.sttp.tapir" %% "tapir-cats-effect" % "1.10.5"
    )
  )

lazy val IOURING = (project in file("examples/IOURING"))
  .dependsOn(root)
  .dependsOn(IOURING_LIB)
  .settings(
    name := "example",
    libraryDependencies ++= Seq(
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % "2.19.1",
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.19.1" % "compile-internal"
    )
  )

lazy val buildJNI = taskKey[Unit]("Build JNI native library")
lazy val IOURING_LIB = (project in file("iouring"))
  .settings(
    organization := "io.github.ollls",
    name := "iouring-quartz-h2",
    libraryDependencies ++= Seq(
      "org.eclipse.collections" % "eclipse-collections-api" % "11.1.0",
      "org.eclipse.collections" % "eclipse-collections" % "11.1.0"
    ),
    // Define the JNI build task
    buildJNI := {
      val sourceDir = baseDirectory.value / "src" / "main" / "native"
      val targetDir = baseDirectory.value / "src" / "main" / "resources"

      // Create target directory if it doesn't exist
      if (!targetDir.exists()) targetDir.mkdirs()

      val JAVA_HOME = System.getenv("JAVA_HOME")
      val LIBURING_PATH = System.getenv("LIBURING_PATH")

      if (JAVA_HOME == null || JAVA_HOME.isEmpty) {
        println("ERROR: JAVA_HOME environment variable is not defined!")
        sys.error("Build aborted: JAVA_HOME environment variable must be defined")
      }
      
      if (LIBURING_PATH == null || LIBURING_PATH.isEmpty) {
        println("ERROR: LIBURING_PATH environment variable is not defined!")
        sys.error("Build aborted: LIBURING_PATH environment variable must be defined")
      }

      val compileCommands = Seq(
        Seq(
          "gcc",
          "-O3",
          "-fPIC",
          s"-I$JAVA_HOME/include",
          s"-I$JAVA_HOME/include/linux",
          s"-I$LIBURING_PATH/src",
          s"-I$LIBURING_PATH/src/include",
          s"-I$LIBURING_PATH/src/include/liburing",
          "-c",
          s"$sourceDir/liburing_file_provider.c",
          "-o",
          "liburing_file_provider.o"
        ),
        Seq(
          "gcc",
          "-O3",
          "-fPIC",
          s"-I$JAVA_HOME/include",
          s"-I$JAVA_HOME/include/linux",
          s"-I$LIBURING_PATH/src",
          s"-I$LIBURING_PATH/src/include",
          s"-I$LIBURING_PATH/src/include/liburing",
          "-c",
          s"$sourceDir/liburing_provider.c",
          "-o",
          "liburing_provider.o"
        ),
        Seq(
          "gcc",
          "-O3",
          "-fPIC",
          s"-I$JAVA_HOME/include",
          s"-I$JAVA_HOME/include/linux",
          s"-I$LIBURING_PATH/src",
          s"-I$LIBURING_PATH/src/include",
          s"-I$LIBURING_PATH/src/include/liburing",
          "-c",
          s"$sourceDir/liburing_socket_provider.c",
          "-o",
          "liburing_socket_provider.o"
        ),
        Seq(
          "gcc",
          "-shared",
          "-o",
          s"$targetDir/libnio_uring.so",
          "liburing_file_provider.o",
          "liburing_provider.o",
          "liburing_socket_provider.o",
          s"$LIBURING_PATH/src/liburing.a"
        )
      )

      println("Building JNI library...")
      compileCommands.foreach { cmd =>
        println(s"Executing: $cmd")
        cmd.!
      }
      println("JNI library built successfully")
    },
    // Make the buildJNI task run before compile
    Compile / compile := ((Compile / compile) dependsOn buildJNI).value,

    // Clean JNI artifacts when cleaning the project
    cleanFiles += baseDirectory.value / "src" / "main" / "native" / "*.o"
  )

scalacOptions ++= Seq(
  "-deprecation",
  "-feature"
)
testFrameworks += new TestFramework("minitest.runner.Framework")

// See https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html for instructions on how to publish to Sonatype.
