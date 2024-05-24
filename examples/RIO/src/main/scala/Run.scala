package example

import cats.effect.{IO, IOApp, Deferred, ExitCode}
import io.quartz.QuartzH2Server
import io.quartz.http2._
import io.quartz.http2.model.{Headers, Method, ContentType, Request, Response}
import io.quartz.http2.model.Method._
import io.quartz.http2.model.StatusCode
import cats.data.ReaderT
import cats.effect.Resource
import io.quartz.http2.routes.HttpRouteRIO
import io.quartz.http2.routes.WebFilter
import io.quartz.http2.routes.RIO
import java.util.Date
import org.typelevel.log4cats.Logger
import io.quartz.MyLogger._

/*
  A rich example on many things at once
  1. environent trait from cats Resource passed to ReaderT/RIO route function
  2. ReaderT based HttpRouteRIO
  3. header attribute "test_tid" data from filter
  4. 403 forbidden on web URI starting from /private
 */
object MyApp extends IOApp {

  val filter: WebFilter = (request: Request) =>
    IO(
      Either.cond(
        !request.uri.getPath().startsWith("/private"),
        request.hdr("test_tid" -> "ABC123Z9292827"),
        Response.Error(StatusCode.Forbidden).asText("Denied: " + request.uri.getPath())
      )
    )

  class Resource1 {
    // time tick, epoch time
    def tick: IO[String] = IO(new Date().getTime().toString())
  }

  val RR: HttpRouteRIO[Resource1] = { case req @ GET -> Root / "tick" =>
    for { // ReaderT monad
      value <- RIO(45) // ReaderT effect, look at the RIO object
      envResource <- ReaderT.ask[IO, Resource1]
      tick <- RIO.liftIO(envResource.tick)
      r <- RIO(Response.Ok().asText(tick + " tid=" + req.headers.get("test_tid").getOrElse("Missing header: test_tid")))
    } yield (r)
  }

  def run(args: List[String]): IO[ExitCode] =
    for {
      r <- IO(
        Resource.make[IO, Resource1](Logger[IO].info("Init resource OK") >> IO(new Resource1()))(_ =>
          Logger[IO].info("discard resource OK")
        )
      )

      ctx <- QuartzH2Server.buildSSLContext("TLS", "keystore.jks", "password")

      exitCode <- r.use(env =>
        new QuartzH2Server("localhost", 8443, 60000, Some(ctx))
          .startRIO(env, RR, filter, sync = false)
      )

    } yield (exitCode)

}
