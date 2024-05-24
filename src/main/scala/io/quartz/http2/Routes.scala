package io.quartz.http2.routes

import cats.effect.IO
import io.quartz.http2.model.{Request, Response}
import cats.data.ReaderT

import org.typelevel.log4cats.Logger
import io.quartz.MyLogger._

type HttpRoute = Request => IO[Option[Response]]
type HttpRouteRIO[Env] = PartialFunction[Request, RIO[Env, Response]]
type HttpRouteIO = PartialFunction[Request, IO[Response]]

/** A type alias representing a web filter, which takes a request and returns an IO that produces either a response or
  * the original request. The filter can be used to transform incoming requests or to perform some validation or
  * authorization logic before passing the request to the HTTP route.
  */
type WebFilter = Request => IO[Either[Response, Request]]

type RIO[E, T] = ReaderT[IO, E, T]

object RIO {
  def apply[T, Env](eval: => T): RIO[Env, T] = ReaderT.liftF(IO(eval))

  def liftIO[T, Env](eval: => IO[T]): RIO[Env, T] = {
    ReaderT.liftF(eval)
  }
  def lift[T, Env](eval: => T): RIO[Env, T] = {
    ReaderT.liftF(IO(eval))
  }
}

object Routes {

  /** Lifts PartialFunction based HttpRouteIO into an Option based HttpRoute applying a specified filter.
    * @param pf
    *   the `HttpRouteIO` that maps requests to `IO` computations producing responses.
    * @param filter
    *   the `WebFilter` to apply to incoming requests before processing.
    * @return
    *   an `HttpRoute` that handles requests based on the given `HttpRouteIO` and `WebFilter`.
    */
  def of(pf: HttpRouteIO, filter: WebFilter): HttpRoute = {
    val route: Request => IO[Option[Response]] = (request: Request) =>
      pf.lift(request) match {
        case Some(c) => c.flatMap(r => (IO(Option(r))))
        case None    => (IO(None))
      }
    (r0: Request) =>
      filter(r0).flatMap {
        case Right(request) => route(request)
        case Left(response) =>
          Logger[IO].error(s"Web filter denied access with response code ${response.code}") >> IO(Some(response))
      }
  }

  /** Creates an `HttpRoute` by applying the given `HttpRouteRIO` to an environment and a `WebFilter`. The resulting
    * `HttpRoute` handles requests based on the given `HttpRouteRIO`, which is a partial function that maps requests to
    * `RIO` computations that may produce an HTTP response. The `Env` parameter represents the environment required by
    * the computations, and is passed to the `RIO` monad using the `run` method. The `WebFilter` is applied to incoming
    * requests before they are passed to the `HttpRouteRIO`, and can be used to pre-process requests or perform
    * authentication or other security checks.
    * @param env
    *   the environment required by the `HttpRouteRIO` computations.
    * @param pf
    *   the `HttpRouteRIO` that maps requests to `RIO` computations producing responses.
    * @param filter
    *   the `WebFilter` to apply to incoming requests before processing.
    * @return
    *   an `HttpRoute` that handles requests based on the given `HttpRouteRIO` and `WebFilter`.
    */
  def of[Env](env: Env, pf: HttpRouteRIO[Env], filter: WebFilter): HttpRoute = {
    val routeIO: Request => RIO[Env, Option[Response]] = (request: Request) =>
      pf.lift(request) match {
        case Some(c) => c.flatMap(r => RIO.liftIO(IO(Option(r))))
        case None    => RIO.liftIO(IO(None))
      }
    val route: HttpRoute = (request: Request) => routeIO(request).run(env)
    (r0: Request) =>
      filter(r0).flatMap {
        case Right(request) => route(request)
        case Left(response) =>
          Logger[IO].error(s"Web filter denied access with response code ${response.code}") >> IO(Some(response))
      }
  }
}
