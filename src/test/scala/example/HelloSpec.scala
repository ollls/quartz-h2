import scala.concurrent.duration._
import cats.implicits._
import cats.effect.IO
import cats.effect.testing.minitest.IOTestSuite
import fs2.{Stream, Chunk}
import fs2.io.file.{Files, Path}
import cats.Parallel

import io.quartz.MyLogger._
import org.typelevel.log4cats.Logger
import ch.qos.logback.classic.Level
import io.quartz.QuartzH2Server
import io.quartz.QuartzH2Client
import io.quartz.http2.routes.HttpRouteIO
import io.quartz.http2.model.{Headers, Method, ContentType, Request, Response}
import io.quartz.http2.model.Method._
import io.quartz.http2._

object SimpleSuite extends IOTestSuite {
  override val timeout = 20.second // Default timeout is 10 seconds

  val PORT = 11443
  val FOLDER_PATH = "/Users/ostrygun/web_root/"
  val BIG_FILE = "img_0278.jpeg"
  val BLOCK_SIZE = 1024 * 14

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
        .asStream(fs2.io.readInputStream(IO(jstream), BLOCK_SIZE, true))
        .contentType(ContentType.contentTypeFromFileName(FILE)))
  }

  test("Parallel streams with GET") {
    val NUMBER_OF_STREAMS = 30
    for {
      _ <- IO(QuartzH2Server.setLoggingLevel(Level.INFO))
      ctx <- QuartzH2Server.buildSSLContext("TLS", "keystore.jks", "password")
      server <- IO(new QuartzH2Server("localhost", PORT.toInt, 16000, ctx))

      fib <- (server.startIO(R, sync = false)).start

      _ <- IO.sleep(1000.millis)

      c <- QuartzH2Client.open(s"https://localhost:$PORT", 1000, ctx)

      program = c.doGet("/" + BIG_FILE).flatMap(_.stream.compile.count)
      list <- Parallel.parReplicateA(NUMBER_OF_STREAMS, program)
      _ <- list.traverse(b => Logger[IO].info(s"Bytes received $b"))

      c <- c.close()
      _ <- server.shutdown
      _ <- fib.join

    } yield (assert(list.size == NUMBER_OF_STREAMS))

  }
}
