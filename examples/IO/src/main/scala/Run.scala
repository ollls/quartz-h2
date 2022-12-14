package example

import cats.effect.{IO, IOApp, Deferred, ExitCode}
import io.quartz.QuartzH2Server
import io.quartz.http2._
import io.quartz.http2.model.{Headers, Method, ContentType, Request, Response}
import io.quartz.http2.model.Method._
import io.quartz.http2.routes.Routes
import io.quartz.http2.routes.HttpRouteIO
import fs2.{Stream, Chunk}
import fs2.io.file.{Files, Path}

object MyApp extends IOApp {

  val R: HttpRouteIO = {

    case req @ POST -> Root / "upload" / StringVar(_) =>
      for {
        reqPath <- IO(Path("/Users/ostrygun/" + req.uri.getPath()))
        u <- req.stream.through(Files[IO].writeAll(reqPath)).compile.drain
        // u <- req.stream.chunks.foreach( c => IO.println( c.size )).compile.drain

      } yield (Response.Ok().asText("OK"))

    // best path for h2spec
    case GET -> Root => IO(Response.Ok().asText("OK"))

    // perf tests
    case GET -> Root / "test" => IO(Response.Ok())

    case GET -> Root / "example" =>
      // how to send data in separate H2 packets of various size.
      val ts = Stream.emits("Block1\n".getBytes())
      val ts2 = ts ++ Stream.emits("Block22\n".getBytes())
      IO(Response.Ok().asStream(ts2))

    case GET -> Root / StringVar(file) =>
      val FOLDER_PATH = "/Users/ostrygun/web_root/"
      val FILE = s"$file"
      val BLOCK_SIZE = 16000
      for {
        jpath <- IO(new java.io.File(FOLDER_PATH + FILE))
        jstream <- IO.blocking(new java.io.FileInputStream(jpath))
      } yield (Response
        .Ok()
        .asStream(fs2.io.readInputStream(IO(jstream), BLOCK_SIZE, true))
        .contentType(ContentType.contentTypeFromFileName(FILE)))

    // your web site files in the folder "web" under web_root.
    // browser path: https://localhost:8443/web/index.html
    case req @ GET -> "site" /: _ =>
      val FOLDER_PATH = "/Users/ostrygun/web_root/"
      val BLOCK_SIZE = 16000
      for {
        reqPath <- IO(req.uri.getPath())
        jpath <- IO(new java.io.File(FOLDER_PATH + reqPath))
        jstream <- IO.blocking(new java.io.FileInputStream(jpath))
        fname <- IO(jpath.getName())
      } yield (Response
        .Ok()
        .asStream(fs2.io.readInputStream(IO(jstream), BLOCK_SIZE, true))
        .contentType(ContentType.contentTypeFromFileName(fname)))
  }

  def run(args: List[String]): IO[ExitCode] =
    for {
      ctx <- QuartzH2Server.buildSSLContext("TLS", "keystore.jks", "password")
      exitCode <- new QuartzH2Server("localhost", 8443, 16000, ctx).startIO(R, sync = false)

    } yield (exitCode)

}
