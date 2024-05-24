package io.quartz.http2

import io.quartz.http2.model.{Request, Response}
import cats.effect.IO
import cats.data.ReaderT

package object routes {
  type HttpRoute = Request => IO[Option[Response]]
  type HttpRouteRIO[Env] = PartialFunction[Request, RIO[Env, Response]]
  type HttpRouteIO = PartialFunction[Request, IO[Response]]

  /** A type alias representing a web filter, which takes a request and returns an IO that produces either a response or
    * the original request. The filter can be used to transform incoming requests or to perform some validation or
    * authorization logic before passing the request to the HTTP route.
    */
  type WebFilter = Request => IO[Either[Response, Request]]

  type RIO[E, T] = ReaderT[IO, E, T]
}
