package io.quartz.http2
import io.quartz.http2.model.{Headers, StatusCode, Request, Response}
import io.quartz.netio.IOChannel
import io.quartz.util.ResponseWriters11
import fs2.{Chunk, Stream}
import io.quartz.http2.Constants.ErrorGen
import io.quartz.http2.Constants.Error
import io.quartz.http2.routes.HttpRoute
import io.quartz.util.Utils
import cats.effect.{IO, Ref}
import cats.implicits._
import org.typelevel.log4cats.Logger
import io.quartz.MyLogger._
import cats.effect.kernel.Deferred
import io.quartz.util.Chunked11

case class HeaderSizeLimitExceeded(msg: String) extends Exception(msg)
case class BadIncomingData(msg: String) extends Exception(msg)

object Http11Connection {

  val MAX_ALLOWED_CONTENT_LEN = 1048576 * 100 // 104 MB

  def make(ch: IOChannel, id: Long, keepAliveMs: Int, httpRoute: HttpRoute) = for {
    streamIdRef <- Ref.of[IO, Int](1)
    c <- IO(new Http11Connection(ch, keepAliveMs, id, streamIdRef, httpRoute))
  } yield (c)
}

class Http11Connection[Env](
    ch: IOChannel,
    keepAliveMs: Int,
    val id: Long,
    streamIdRef: Ref[IO, Int],
    httpRoute: HttpRoute
) {

  def shutdown: IO[Unit] =
    Logger[IO].debug("Http11Connection.shutdown")

  def translateHeadersFrom11to2(headers: Headers): Headers = {
    val map = headers.tbl.map {
      case (k, v) => {
        val k1 = k.toLowerCase() match {
          case "host"    => ":authority"
          case k: String => k
        }
        (k1, v)
      }
    }

    new Headers(map)
  }

  def processIncoming(
      headers0: Headers,
      leftOver_tls: Chunk[Byte],
      refStart: Ref[IO, Boolean]
  ): IO[Unit] = {
    for {
      streamId <- streamIdRef.getAndUpdate(_ + 2)
      start <- refStart.get
      v <-
        if (start) IO((headers0, leftOver_tls))
        else
          for {
            r0 <- Utils.splitHeadersAndBody(ch, keepAliveMs, Chunk.empty[Byte])
            headers_bytes = r0._1
            leftover_bytes = r0._2
            v1 <- Utils.getHttpHeaderAndLeftover(headers_bytes, ch.secure())
          } yield (v1._1, leftover_bytes)

      headers_received = v._1
      leftover = v._2

      headers <- IO(translateHeadersFrom11to2(headers_received))

      _ <- refStart.set(false)

      validate <- IO
        .raiseError(new BadIncomingData("bad inbound data or invalid request"))
        .whenA(headers.validatePseudoHeaders == false)

      body_stream = (fs2.Stream(leftover) ++ fs2.Stream.repeatEval(ch.read(keepAliveMs))).flatMap(fs2.Stream.chunk(_))

      isChunked <- IO(headers.getMval("transfer-encoding").exists(_.equalsIgnoreCase("chunked")))
      isContinue <- IO(headers.get("Expect").getOrElse("").equalsIgnoreCase("100-continue"))

      _ <- ResponseWriters11.writeNoBodyResponse(ch, StatusCode.Continue, false).whenA(isChunked && isContinue)

      isWebSocket <- IO(headers.get("upgrade").contains("websocket"))

      contentLen <- IO(headers.get("content-length").getOrElse("0"))
      contentLenL <- IO.fromTry(scala.util.Try(contentLen.toLong))
      _ <- Logger[IO].trace(s"content-length = $contentLen")
      _ <-
        if (contentLenL > Http11Connection.MAX_ALLOWED_CONTENT_LEN) IO.raiseError(new Exception("ContentLenTooBig"))
        else IO.unit

      /* TODO inbbound CHUNKED stream */
      stream <-
        if (isChunked) IO(Chunked11.makeChunkedStream(body_stream, keepAliveMs).unchunks)
        else IO(body_stream.take(contentLenL))

      emptyTH <- Deferred[IO, Headers] // no trailing headers for 1.1
      _ <- emptyTH.complete(Headers()) // complete with empty

      http11request <- IO(Request(id, streamId, headers, stream, ch.secure(), ch.sniServerNames(), emptyTH))

      _ <- route2(streamId, http11request, isChunked)

    } yield ()
  }

  def route2(streamId: Int, request: Request, requestChunked: Boolean) = for {
    _ <- Logger[IO].debug(
      s"HTTP/1.1 chunked streamId = $streamId ${request.method.name} ${request.path} "
    )
    _ <- Logger[IO].debug("request.headers: " + request.headers.printHeaders(" | "))

    _ <- IO
      .raiseError(ErrorGen(streamId, Error.COMPRESSION_ERROR, "http11 empty headers"))
      .whenA(request.headers.tbl.size == 0)

    _ <- IO
      .raiseError(ErrorGen(streamId, Error.PROTOCOL_ERROR, "Upercase letters in the header keys"))
      .whenA(request.headers.ensureLowerCase == false)

    response_o <- httpRoute(request).handleErrorWith {
      case e: (java.io.FileNotFoundException) =>
        Logger[IO].debug(e.toString) >> IO(None)

      case e: (java.nio.file.NoSuchFileException) =>
        Logger[IO].debug(e.toString) >> IO(None)

      case e =>
        Logger[IO].debug(e.toString) >>
          IO(Some(Response.Error(StatusCode.InternalServerError)))
    }

    _ <- response_o match {
      case Some(response) =>
        for {
          _ <- ResponseWriters11.writeFullResponseFromStreamChunked(
            ch,
            response.hdr(("Transfer-Encoding" -> "chunked"))
          )
          _ <- Logger[IO].info(
            s"HTTP/1.1 connId=$id streamId=$streamId ${request.method.name} ${request.path} chunked=$requestChunked ${response.code.toString()}"
          )
        } yield ()
      case None =>
        Logger[IO].error(
          s"HTTP/1.1 connId=$id streamId=$streamId ${request.method.name} ${request.path} ${StatusCode.NotFound.toString()}"
        ) *> ResponseWriters11.writeNoBodyResponse(ch, StatusCode.NotFound, false)
    }

  } yield ()

}
