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
import io.quartz.websocket.Websocket

/**
 * HTTP/1.1 Connection Handler
 * --------------------------
 * Manages HTTP/1.1 connections with support for chunked encoding, websockets, and request routing.
 * Translates between HTTP/1.1 and HTTP/2 header formats for internal processing consistency.
 * 
 * Key features:
 * - Content length validation and limits
 * - Chunked transfer encoding support
 * - WebSocket protocol upgrade handling
 * - Integration with HTTP routing system
 */
case class HeaderSizeLimitExceeded(msg: String) extends Exception(msg)
case class BadIncomingData(msg: String) extends Exception(msg)

object Http11Connection {

  /** Maximum allowed content length in bytes (100MB) */
  val MAX_ALLOWED_CONTENT_LEN = 1048576 * 100 // 100 MB

  /**
   * Creates a new HTTP/1.1 connection handler
   * @param ch The underlying IO channel
   * @param id Connection identifier
   * @param keepAliveMs Keep-alive timeout in milliseconds
   * @param httpRoute HTTP route for processing requests
   * @return A new HTTP/1.1 connection instance
   */
  def make(ch: IOChannel, id: Long, keepAliveMs: Int, httpRoute: HttpRoute): IO[Http11Connection[Any]] = for {
    streamIdRef <- Ref.of[IO, Int](1)
    c <- IO(new Http11Connection(ch, keepAliveMs, id, streamIdRef, httpRoute))
  } yield c
}

class Http11Connection[Env](
    ch: IOChannel,
    keepAliveMs: Int,
    val id: Long,
    streamIdRef: Ref[IO, Int],
    httpRoute: HttpRoute
) {

  /**
   * Shuts down the connection and logs the event
   * @return IO unit when complete
   */
  def shutdown: IO[Unit] =
    Logger[IO].debug(s"Http11Connection.shutdown for connection $id")

  /**
   * Translates HTTP/1.1 headers to HTTP/2 format
   * @param headers The HTTP/1.1 headers
   * @return Headers in HTTP/2 format
   */
  def translateHeadersFrom11to2(headers: Headers): Headers = {
    val map = headers.tbl.map((k, v) => {
      val k1 = k.toLowerCase() match {
        case "host"    => ":authority"
        case k: String => k
      }
      (k1, v)
    })

    new Headers(map)
  }

  /**
   * Processes incoming HTTP/1.1 requests
   * @param headers0 Initial headers if available
   * @param leftOver_tls Any leftover bytes from TLS processing
   * @param refStart Reference to track if this is the start of processing
   * @return IO unit when request processing is complete
   */
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

      _ <- IO
        .raiseError(new BadIncomingData("Bad inbound data or invalid request: missing or invalid pseudo-headers"))
        .whenA(headers.validatePseudoHeaders == false)

      body_stream = (fs2.Stream(leftover) ++ fs2.Stream.repeatEval(ch.read(keepAliveMs))).flatMap(fs2.Stream.chunk(_))

      isChunked <- IO(headers.getMval("transfer-encoding").exists(_.equalsIgnoreCase("chunked")))
      isContinue <- IO(headers.get("Expect").getOrElse("").equalsIgnoreCase("100-continue"))

      _ <- ResponseWriters11.writeNoBodyResponse(ch, StatusCode.Continue, false).whenA(isChunked && isContinue)

      isWebSocket <- IO(headers.get("upgrade").contains("websocket"))

      contentLen <- IO(headers.get("content-length").getOrElse("0"))
      contentLenL <- IO.fromTry(scala.util.Try(contentLen.toLong))
        .handleErrorWith(_ => IO.raiseError(new BadIncomingData(s"Invalid content-length header value: $contentLen")))
      
      _ <- Logger[IO].debug(s"content-length = $contentLen")
      _ <-
        if (contentLenL > Http11Connection.MAX_ALLOWED_CONTENT_LEN) 
          IO.raiseError(new HeaderSizeLimitExceeded(s"Content length $contentLenL exceeds maximum allowed size ${Http11Connection.MAX_ALLOWED_CONTENT_LEN}"))
        else IO.unit

      stream <-
        if (isWebSocket) IO(body_stream)
        else if (isChunked) IO(Chunked11.makeChunkedStream(body_stream, keepAliveMs).unchunks)
        else IO(body_stream.take(contentLenL))

      emptyTH <- Deferred[IO, Headers] // no trailing headers for 1.1
      _ <- emptyTH.complete(Headers()) // complete with empty

      http11request <- IO(Request(id, streamId, headers, stream, ch.secure(), ch.sniServerNames(), emptyTH))

      _ <- route2(streamId, http11request, isChunked)

    } yield ()
  }

  /**
   * Routes the request to the appropriate handler and processes the response
   * @param streamId Stream identifier for the request
   * @param request The HTTP request
   * @param requestChunked Whether the request uses chunked encoding
   * @return IO unit when routing is complete
   */
  def route2(streamId: Int, request: Request, requestChunked: Boolean): IO[Unit] = for {
    _ <- Logger[IO].debug(
      s"HTTP/1.1 request streamId=$streamId ${request.method.name} ${request.path} chunked=$requestChunked"
    )
    _ <- Logger[IO].debug("Request headers: " + request.headers.printHeaders(" | "))

    _ <- IO
      .raiseError(ErrorGen(streamId, Error.COMPRESSION_ERROR, "HTTP/1.1 request with empty headers"))
      .whenA(request.headers.tbl.size == 0)

    _ <- IO
      .raiseError(ErrorGen(streamId, Error.PROTOCOL_ERROR, "Uppercase letters in header keys are not allowed"))
      .whenA(request.headers.ensureLowerCase == false)

    response_o: Option[Response] <- (httpRoute(request)).handleErrorWith {
      case e: (java.io.FileNotFoundException | java.nio.file.NoSuchFileException) =>
        Logger[IO].debug(s"File not found: ${e.getMessage}") >> IO(None)
      case e =>
        Logger[IO].error(s"Error processing request: ${e.getMessage}") >>
          IO(Some(Response.Error(StatusCode.InternalServerError)))
    }

    _ <- response_o match {
      case Some(response) =>
        response.websocket match {
          case None =>
            for {
              _ <- ResponseWriters11.writeFullResponseFromStreamChunked(
                ch,
                response.hdr(("Transfer-Encoding" -> "chunked"))
              )
              _ <- Logger[IO].info(
                s"HTTP/1.1 connId=$id streamId=$streamId ${request.method.name} ${request.path} chunked=$requestChunked ${response.code.toString()}"
              )
              _ <- Logger[IO].debug("Response headers: " + response.headers.printHeaders(" | "))
            } yield ()
          case Some(pipe) =>
            val wsctx = Websocket()
            for {
              _ <- wsctx.accept(ch, request)
              _ <- Logger[IO].debug("WebSocket connection established")
              // apply a Pipe
              p <- pipe
              inFrames <- IO(wsctx.makeFrameStream(request.stream))
              outFrames <- IO(inFrames.through(p))
              _ <- outFrames.foreach(frame => wsctx.writeFrame(ch, frame)).compile.drain

            } yield ()
        }
      case None =>
        Logger[IO].error(
          s"HTTP/1.1 connId=$id streamId=$streamId ${request.method.name} ${request.path} ${StatusCode.NotFound.toString()}"
        ) *> ResponseWriters11.writeNoBodyResponse(ch, StatusCode.NotFound, false)
    }

  } yield ()

}
