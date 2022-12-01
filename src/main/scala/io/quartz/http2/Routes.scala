package io.quartz.http2.routes

import cats.effect.IO
import cats.effect.{Sync, LiftIO}
import cats.implicits._
import fs2.Stream
import io.quartz.http2.model.{Request, Response, Headers, StatusCode, Method}
import cats.Monad
import cats.data.ReaderT

type HttpRoute = Request => IO[Option[Response]]
type HttpRouteRIO[Env] = PartialFunction[Request, RIO[Env, Response]]
type HttpRouteIO = PartialFunction[Request, IO[Response]]

type RIO[E, T] = ReaderT[IO, E, T]

object RIO {
  def apply[T, Env](eval: => T): RIO[Env, T] = ReaderT.liftF(IO(eval))

  def liftIO[T, Env](eval: => IO[T]): RIO[Env, T] = {
    ReaderT.liftF(eval)
  }
  def lift[T, Env](eval: => T): RIO[Env, T] = {
    ReaderT.liftF(IO(eval))
  }
  /*
  object implicits {
    implicit def lift[T, Env](eval: => T): RIO[Env, T] = RIO.lift(eval)
  } */
}

object Routes {
  // route where environment needs to be provided later to generate regular HttpRoute
  //def of[Env](pf: HttpRouteRIO[Env]): HttpRouteRIO[Env] = {
  //  pf
  //}

  
  //route withot environment, gives direct HttpRoute
  def of[Env](pf: HttpRouteIO): HttpRoute = {
    val T1: Request => IO[Option[Response]] = (request: Request) =>
      pf.lift(request) match {
        case Some(c) => c.flatMap(r => (IO(Option(r))))
        case None    => (IO(None))
      }
    T1
  }

  /*
  //route with immediately provided envronment, gives ready to use HttpRoute
  def of[Env](env: Env)(pf: PartialFunction[Request, RIO[Env, Response]]): HttpRoute = {
    val T1: Request => ReaderT[IO, Env, Option[Response]] = (request: Request) =>
      pf.lift(request) match {
        case Some(c) => c.flatMap(r => ReaderT.liftF(IO(Option(r))))
        case None    => ReaderT.liftF(IO(None))
      }
    (request: Request) => T1(request).run(env)
  }*/
}
