package io.quartz.http2.routes

import cats.effect.IO
import cats.effect.{Sync, LiftIO}
import cats.implicits._
import fs2.Stream
import io.quartz.http2.model.{Request, Response, Headers, StatusCode, Method}
import cats.Monad
import cats.data.ReaderT

import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import io.quartz.MyLogger._

type HttpRoute = Request => IO[Option[Response]]
type HttpRouteRIO[Env] = PartialFunction[Request, RIO[Env, Response]]
type HttpRouteIO = PartialFunction[Request, IO[Response]]
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
  // route withot environment, gives direct HttpRoute
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

  //route with environment
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
