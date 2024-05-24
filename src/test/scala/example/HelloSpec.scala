import scala.concurrent.duration._
import cats.implicits._
import cats.effect.IO
import cats.effect.testing.minitest.IOTestSuite
import cats.Parallel

import io.quartz.MyLogger._
import org.typelevel.log4cats.Logger
import ch.qos.logback.classic.Level
import io.quartz.QuartzH2Server
import io.quartz.QuartzH2Client
import io.quartz.http2.routes.HttpRouteIO
import io.quartz.http2.model.{Method, ContentType, Request, Response}
import io.quartz.http2.model.Method._
import io.quartz.http2._

object QuartzH2ClientServerSuite extends IOTestSuite {
  override val timeout = 120.second // Default timeout is 10 seconds

  val PORT = 11443
  val FOLDER_PATH = "/Users/ostrygun/web_root/"
  val BIG_FILE = "img_0278.jpeg"
  val BLOCK_SIZE = 1024 * 14
  val NUMBER_OF_STREAMS = 24

  QuartzH2Server.setLoggingLevel(Level.INFO)

  val R: HttpRouteIO = {
    case req @ GET -> Root =>
      for {
        x <- req.stream.compile.count
      } yield (Response.Ok().asText(s"OK bytes received: $x"))

    case GET -> Root / StringVar(file) =>
      val FILE = s"$file"
      val BLOCK_SIZE = 16000
      for {
        jpath <- IO(new java.io.File(FOLDER_PATH + FILE))
        jstream <- IO.blocking(new java.io.FileInputStream(jpath))
      } yield (Response
        .Ok()
        .asStream(fs2.io.readInputStream[IO](IO(jstream), BLOCK_SIZE, true))
        .contentType(ContentType.contentTypeFromFileName(FILE)))

    case req @ POST -> Root / "upload" / StringVar(_) =>
      for {
        bytes <- req.stream.compile.count
      } yield (Response.Ok().asText(s"$bytes"))
  }

  test("Parallel streams with GET") {
    for {
      ctx <- QuartzH2Server.buildSSLContext("TLS", "keystore.jks", "password")
      server <- IO(new QuartzH2Server("localhost", PORT.toInt, 46000, Some(ctx)))

      fib <- (server.startIO(R, sync = false)).start

      _ <- IO.sleep(1000.millis)

      c <- QuartzH2Client.open(s"https://localhost:$PORT", 46000, ctx)

      program = c.doGet("/" + BIG_FILE).flatMap(_.stream.compile.count)
      list <- Parallel.parReplicateA(NUMBER_OF_STREAMS, program)
      _ <- list.traverse(b => Logger[IO].info(s"Bytes received $b"))

      c <- c.close()
      _ <- server.shutdown
      _ <- fib.join

    } yield (assert(list.size == NUMBER_OF_STREAMS))
  }
  test("proper 404 handling while sending data") {
    for {
      ctx <- QuartzH2Server.buildSSLContext("TLS", "keystore.jks", "password")
      server <- IO(new QuartzH2Server("localhost", PORT.toInt, 46000, Some(ctx)))
      fib <- (server.startIO(R, sync = false)).start
      _ <- IO.sleep(1000.millis)
      c <- QuartzH2Client.open(s"https://localhost:$PORT", 46000, ctx)

      path <- IO(new java.io.File(FOLDER_PATH + BIG_FILE))
      fileStream <- IO(new java.io.FileInputStream(path))

      res <- c.doPost("/" + BIG_FILE, fs2.io.readInputStream[IO](IO(fileStream), BLOCK_SIZE, true))

      _ <- Logger[IO].info(s"Response status code=${res.status}")

      c <- c.close()
      _ <- server.shutdown
      _ <- fib.join
    } yield (assert(res.status.value == 404))
  }

  test("Parallel streams with POST") {
    for {
      ctx <- QuartzH2Server.buildSSLContext("TLS", "keystore.jks", "password")
      server <- IO(new QuartzH2Server("localhost", PORT.toInt, 46000, Some(ctx)))
      fib <- (server.startIO(R, sync = false)).start
      _ <- IO.sleep(1000.millis)
      c <- QuartzH2Client.open(s"https://localhost:$PORT", 46000, ctx)
      path <- IO(new java.io.File(FOLDER_PATH + BIG_FILE))

      program = for {
        fileStream <- IO(new java.io.FileInputStream(path))
        r <- c.doPost("/upload/" + BIG_FILE, fs2.io.readInputStream[IO](IO(fileStream), BLOCK_SIZE, true))
        bytes <- r.bodyAsText.map( _.toInt)
      } yield (bytes)

      list <- Parallel.parReplicateA(NUMBER_OF_STREAMS, program)
      _ <- list.traverse(b => Logger[IO].info(s"Bytes received by server $b"))

      c <- c.close()
      _ <- server.shutdown
      _ <- fib.join
    } yield (assert(true))

  }

}
