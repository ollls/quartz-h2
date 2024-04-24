package io.quartz.sttp

import fs2.Stream
import cats.effect.IO
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.integ.cats.effect.CatsMonadError
import sttp.tapir.server.interceptor.reject.RejectInterceptor
import sttp.tapir.server.interceptor.RequestResult
import sttp.tapir.server.interpreter.{
  BodyListener,
  FilterServerEndpoints,
  ServerInterpreter
}

import io.quartz.http2.model.Request
import io.quartz.http2.model.Headers
import io.quartz.sttp.QuartzH2BodyListener
import io.quartz.sttp.capabilities.fs2.Fs2IOStreams

trait QuartzH2ServerInterpreter {

  def serverOptions: QuartzH2ServerOptions[IO] =
    QuartzH2ServerOptions.default[IO]

  def toResponse(
      interpreter: ServerInterpreter[
        Nothing,
        IO,
        Stream[IO, Byte],
        Fs2IOStreams
      ],
      serverRequest: QuartzH2Request
  ) = {
    interpreter(serverRequest).flatMap {

      case _: RequestResult.Failure => IO(None)

      case RequestResult.Response(r) => {
        val code = io.quartz.http2.model.StatusCode(r.code.code)
        val hdrs: io.quartz.http2.model.Headers =
          r.headers.foldLeft(new Headers())((z, h) => z + (h.name, h.value))
        val hdrs1 = hdrs + (":status", code.toString())
        val stream = r.body.getOrElse(Stream.empty)

        val rsp = io.quartz.http2.model.Response(code, hdrs1, stream)

        IO(Some(rsp))
      }
    }
  }

  def toRoutes(
      serverEndpoints: List[ServerEndpoint[Any, IO]]
  ) = {
    implicit val monad: CatsMonadError[IO] = new CatsMonadError[IO]
    implicit val bodyListener: BodyListener[IO, QuartzH2ResponseBody] =
      new QuartzH2BodyListener()

    val interpreter = new ServerInterpreter(
      FilterServerEndpoints(serverEndpoints),
      new QuartzH2RequestBody(serverOptions),
      new QuartzH2ToResponseBody(serverOptions),
      RejectInterceptor
        .disableWhenSingleEndpoint(
          serverOptions.interceptors,
          serverEndpoints
        ),
      serverOptions.deleteFile
    )

    val req_f = (req: Request) => {
      val serverRequest = QuartzH2Request(req)
      toResponse(interpreter, serverRequest)
    }
    req_f
  }
}

object QuartzH2ServerInterpreter {

  def apply(): QuartzH2ServerInterpreter = {
    new QuartzH2ServerInterpreter() {}
  }

  def apply(serveOpt: QuartzH2ServerOptions[IO]): QuartzH2ServerInterpreter = {
    new QuartzH2ServerInterpreter {
      override def serverOptions: QuartzH2ServerOptions[IO] = serveOpt
    }
  }

}
