package example

import cats.effect.{IO, IOApp, Deferred, ExitCode}
import io.quartz.QuartzH2Server
import io.quartz.http2._
import io.quartz.http2.model.{Headers, Method, ContentType, Request, Response}
import io.quartz.http2.model.Method._
import io.quartz.http2.model.Cookie
import io.quartz.http2.routes.Routes
import io.quartz.http2.routes.HttpRouteIO
import fs2.{Stream, Chunk}
import fs2.io.file.{Files, Path}
import io.quartz.util.MultiPart
import io.quartz.http2.model.StatusCode
import io.quartz.http2.routes.WebFilter
import cats.syntax.all.catsSyntaxApplicativeByName
import concurrent.duration.DurationInt
import org.typelevel.log4cats.Logger
import io.quartz.MyLogger._
import ch.qos.logback.classic.Level
import io.quartz.util.HttpRangeRequest

object param1 extends QueryParam("param1")
object param2 extends QueryParam("param2")

object MyApp extends IOApp {

  val filter: WebFilter = (request: Request) =>
    IO(
      Either.cond(
        !request.uri.getPath().endsWith("na.txt"),
        request.hdr("test_tid" -> "ABC123Z9292827"),
        Response
          .Error(StatusCode.Forbidden)
          .asText("Denied: " + request.uri.getPath())
      )
    )

  var HOME_DIR = "/Users/ostrygun/" // last slash is important!

  val R: HttpRouteIO = {
    case req @ GET -> Root / "ldt" =>
      for {
        time <- IO(java.time.LocalDateTime.now())
      } yield (Response.Ok().asText(time.toString()))

    case req @ GET -> Root / "snihost" =>
      for {
        _ <-
          req.stream.compile.drain // properly ignore incoming data, we must flush it, generaly if you sure there will be no data, you can ignore.
        result_text <- IO(req.sniServerNames match {
          case Some(hosts) =>
            s"Host names in TLS SNI extension: ${hosts.mkString(",")}"
          case None => "No TLS SNI host names provided or unsecure connection"
        })
      } yield (Response.Ok().asText(result_text))

    case req @ GET -> Root / "headers" =>
      IO(
        Response
          .Ok()
          .asText(
            s"connId=${req.connId} streamId=${req.streamId}\n${req.headers.printHeaders}"
          )
      )

    case req @ GET -> "pub" /: remainig_path =>
      IO(Response.Ok().asText(remainig_path.toString()))

    // GET with two parameters
    case req @ GET -> Root / "hello" / "1" / "2" / "user2" :? param1(
          test
        ) :? param2(test2) =>
      IO(Response.Ok().asText("param1=" + test + "  " + "param2=" + test2))

    // GET with paameter, cookies and custom headers
    case GET -> Root / "hello" / "user" / StringVar(userId) :? param1(par) =>
      val headers = Headers(
        "procid" -> "header_value_from_server",
        "content-type" -> ContentType.Plain.toString
      )
      val c1 = Cookie("testCookie1", "ABCD", secure = true)
      val c2 = Cookie("testCookie2", "ABCDEFG", secure = false)
      val c3 =
        Cookie(
          "testCookie3",
          "1A8BD0FC645E0",
          secure = false,
          expires = Some(java.time.ZonedDateTime.now.plusHours(5))
        )
      IO(
        Response
          .Ok()
          .hdr(headers)
          .cookie(c1)
          .cookie(c2)
          .cookie(c3)
          .asText(s"$userId with para1 $par")
      )

    // automatic multi-part upload, file names preserved
    case req @ POST -> Root / "mpart" =>
      MultiPart.writeAll(req, HOME_DIR) *> IO(Response.Ok())

    case req @ POST -> Root / "upload" / StringVar(_) =>
      for {
        reqPath <- IO(Path(HOME_DIR + req.uri.getPath()))
        _ <- Logger[IO].info(s"Saving: ${reqPath.toString}")
        _ <- req.stream.through(Files[IO].writeAll(reqPath)).compile.drain
        // _ <- req.stream.chunks.foreach( bb => IO.sleep( 1000.millis)).compile.drain

      } yield (Response.Ok().asText("OK"))

    // best path for h2spec
    case req @ GET -> Root =>
      for {
        _ <- req.stream.compile.drain
      } yield (Response.Ok().asText("OK"))

    // perf tests
    case GET -> Root / "test" => IO(Response.Ok())

    // virtual hosting with TLS SNI.
    // example with SniHostName passed as parmeter for "!" operator
    case "localhost" ! GET -> Root / "example" =>
      // how to send data in separate H2 packets of various size.
      val ts = Stream.emits("Block1\n".getBytes())
      val ts2 = ts ++ Stream.emits("Block22\n".getBytes())
      IO(Response.Ok().asStream(ts2))

    // Any regular file or mp4 videos wih Http Range.
    // Ranged Video streaming tested with Firefox,Safari,Chrome
    case req @ GET -> Root / StringVar(file) =>
      val FOLDER_PATH = "web_root/"
      val FILE = s"$file"
      val BLOCK_SIZE = 32000
      for {
        jpath <- IO(new java.io.File(FOLDER_PATH + FILE))
      } yield (HttpRangeRequest
        .makeResponse(req, jpath, ContentType.Video_MP4, BLOCK_SIZE))

    // your web site files in the folder "web" under web_root.
    // browser path: https://localhost:8443/web/index.html
    case req @ GET -> "doc" /: _ =>
      val FOLDER_PATH = "web_root/"
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
      _ <- IO(QuartzH2Server.setLoggingLevel(Level.DEBUG))
        .whenA(args.find(_ == "--debug").isDefined)
      _ <- IO(QuartzH2Server.setLoggingLevel(Level.ERROR))
        .whenA(args.find(_ == "--error").isDefined)
      _ <- IO(QuartzH2Server.setLoggingLevel(Level.OFF))
        .whenA(args.find(_ == "--off").isDefined)
      _ <- IO(QuartzH2Server.setLoggingLevel(Level.TRACE))
        .whenA(args.find(_ == "--trace").isDefined)

      ctx <- QuartzH2Server.buildSSLContext(
        "TLSv1.3",
        "keystore.jks",
        "password"
      )
      exitCode <- new QuartzH2Server(
        "10.0.0.6",
        8443,
        12000,
        Some(ctx)
      ) // , incomingWinSize = 1000000)
        .iouring_startIO(R, filter)

    } yield (exitCode)

}
