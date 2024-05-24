package io.quartz.sttp

import fs2.{Stream, Pipe}
import cats.effect.IO
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.integ.cats.effect.CatsMonadError
import sttp.tapir.server.interceptor.reject.RejectInterceptor
import sttp.tapir.server.interceptor.RequestResult
import sttp.tapir.server.interpreter.{BodyListener, FilterServerEndpoints, ServerInterpreter}
import sttp.capabilities.WebSockets
import io.quartz.websocket.{WebSocketFrame => QuartzH2WebSocketFrame}

import io.quartz.http2.model.Request
import io.quartz.http2.model.Headers
import io.quartz.sttp.QuartzH2BodyListener
import io.quartz.sttp.capabilities.fs2.Fs2IOStreams
import sttp.tapir.server.model.ServerResponse
import io.quartz.http2.model.Response

trait QuartzH2ServerInterpreter {

  def serverOptions: QuartzH2ServerOptions[IO] =
    QuartzH2ServerOptions.default[IO]

  private def serverResponseToQuartzH2Response(r: ServerResponse[QuartzH2ResponseBody]) = {
    val code = io.quartz.http2.model.StatusCode(r.code.code)
    val hdrs: io.quartz.http2.model.Headers =
      r.headers.foldLeft(new Headers())((z, h) => z + (h.name.toLowerCase(), h.value))
    val hdrs1 = hdrs + (":status", code.toString())

    r.body match {
      case Some(Left(pipeF)) => Response(code, hdrs1, Stream.empty, Some(pipeF))
      case Some(Right((stream: fs2.Stream[IO, Byte], Some(boundary)))) => {
        val new_content_type =
          r.contentType.flatMap(ct => if (ct.startsWith("multipart")) Some(ct + "; boundary=" + boundary) else None)
        val hdrs2 = new_content_type.map(ct => hdrs1.drop("content-type") + ("content-type" -> ct))
        io.quartz.http2.model.Response(code, if (hdrs2.isDefined) hdrs2.get else hdrs1, stream)
      }
      case Some(Right((stream: fs2.Stream[IO, Byte], None))) => {
        io.quartz.http2.model.Response(code, hdrs1, stream)
      }
      case None => Response(code, hdrs1, Stream.empty)
    }
  }

  def toResponse(
      interpreter: ServerInterpreter[Fs2IOStreams & WebSockets, IO, Either[IO[
        Pipe[IO, QuartzH2WebSocketFrame, QuartzH2WebSocketFrame]
      ], (Stream[IO, Byte], Option[String])], Fs2IOStreams],
      serverRequest: QuartzH2Request
  ) = {
    interpreter(serverRequest).flatMap {
      case x: RequestResult.Failure => { IO(None) }
      case RequestResult.Response(r) => {
        val rsp = serverResponseToQuartzH2Response(r)
        IO(Some(rsp))
      }
    }
  }

  def toRoutes(
      serverEndpoints: List[ServerEndpoint[Fs2IOStreams & WebSockets, IO]]
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
