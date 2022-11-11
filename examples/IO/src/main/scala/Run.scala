package example

import cats.effect.{IO, IOApp, Deferred, ExitCode}
import io.quartz.QuartzH2Server
import io.quartz.http2._
import io.quartz.http2.model.{Headers, Method, ContentType, Request, Response}
import io.quartz.http2.model.Method._
import io.quartz.http2.routes.Routes
import io.quartz.http2.routes.HttpRouteIO

//import cats.data.Kleisli, cats.implicits._

object MyApp extends IOApp {

  val text = "Hello World!"

  val ROOT_CATALOG = "/Users/ostrygun/web_root"

  val R : HttpRouteIO = { 
    //best path for h2spec
    case GET -> Root => IO( Response.Ok().asText("OK")) 
    //uncomment this line for perf tests
    //case GET -> Root / "test" => IO( Response.Ok()) 
  }


  /*
  def routes[Env](e: Env) = {
    val R = Routes.of(e) {  //env and RIO
      case GET -> Root / "error" => RIO.liftIO(IO.raiseError(new Exception("error"))) // *> RIO(Response.Ok())
      case req @ GET -> "web" /: _ =>
        for {
          reqPath <- RIO(req.uri.getPath())  //don't need RIO with import io.quartz.http2.routes.RIO.implicits.lift
          text <- ReaderT.ask
          t_f <- RIO.lift(new java.io.File(ROOT_CATALOG + reqPath))
          fname <- RIO(t_f.getName())
          stream <- ReaderT.liftF(IO.blocking(new java.io.FileInputStream(t_f)))
          rsp <- RIO.lift(
            Response
              .Ok()
              .asStream(fs2.io.readInputStream(IO(stream), 16000, true))
              .contentType(ContentType.contentTypeFromFileName(fname))
          )
        } yield (rsp)
      case req @ GET -> Root =>
        for {
          v <- RIO.liftIO(req.stream.compile.toVector)

          arr <- RIO(new String(v.toArray))
          rep <- RIO(
            Response
              .Ok()
              .asText(text /*new java.util.Date().getTime().toString()*/ )
              .contentType(ContentType.Plain)
          )
        } yield (rep)
      case req @ GET -> Root / "health" => RIO(Response.Ok())
      case GET -> Root / "pic" =>
        for {
          fp <- RIO.liftIO(
            IO.blocking(
              new java.io.FileInputStream(new java.io.File("IMG_0278.jpeg"))
            )
          )
        } yield (Response
          .Ok()
          .asStream(fs2.io.readInputStream[IO](IO(fp), 16000, true))
          .contentType(ContentType.Image_JPEG))
    }

    R
  }*/
  /*
    def run_justIO(args: List[String]): IO[ExitCode] =
    for {
      ctx <- QuartzH2Server.buildSSLContext("TLS", "keystore.jks", "password")
      exitCode <- new QuartzH2Server("localhost", 8443, 60000, ctx).start( routes( "text environment") )
    } yield (exitCode)*/

  def run(args: List[String]): IO[ExitCode] =
    for {
      ctx <- QuartzH2Server.buildSSLContext("TLS", "keystore.jks", "password")
      exitCode <- new QuartzH2Server("localhost", 8443, 60000, ctx).startIO( R )

    } yield (exitCode)

}
