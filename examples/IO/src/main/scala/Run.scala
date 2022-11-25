package example

import cats.effect.{IO, IOApp, Deferred, ExitCode}
import io.quartz.QuartzH2Server
import io.quartz.http2._
import io.quartz.http2.model.{Headers, Method, ContentType, Request, Response}
import io.quartz.http2.model.Method._
import io.quartz.http2.routes.Routes
import io.quartz.http2.routes.HttpRouteIO
import fs2.{Stream, Chunk}

//import cats.data.Kleisli, cats.implicits._

object MyApp extends IOApp {

  val text = "Hello World!"

  val ROOT_CATALOG = "/Users/yourusername/web_root"

  val R: HttpRouteIO = {
    // best path for h2spec
    case GET -> Root => IO(Response.Ok().asText("OK"))

    // perf tests
    case GET -> Root / "test" => IO(Response.Ok())

    case GET -> Root / "example" =>
      // how to send data in separate H2 packets of various size.
      val ts = Stream.emits("Block1\n".getBytes())
      val ts2 = ts ++ Stream.emits("Block22\n".getBytes())
      IO(Response.Ok().asStream(ts2))

    case GET -> Root / "pic" =>
      val FOLDER_PATH = "/Users/ostrygun/web_root/"
      val FILE = "IMG_0278.jpeg"
      val BLOCK_SIZE = 16000
      for {
        jpath <- IO(new java.io.File(FOLDER_PATH + FILE))
        jstream <- IO.blocking(new java.io.FileInputStream( jpath ) )
      } yield ( Response
              .Ok()
              .asStream(fs2.io.readInputStream(IO(jstream), BLOCK_SIZE, true))
              .contentType(ContentType.contentTypeFromFileName(FILE)) )

  }

  def run(args: List[String]): IO[ExitCode] =
    for {
      ctx <- QuartzH2Server.buildSSLContext("TLS", "keystore.jks", "password")
      exitCode <- new QuartzH2Server("localhost", 8443, 60000, ctx).startIO(R, sync = false)

    } yield (exitCode)

}
