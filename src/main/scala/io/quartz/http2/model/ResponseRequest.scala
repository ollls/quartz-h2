package io.quartz.http2.model

import cats.effect.{IO, Deferred}
import fs2.{Stream, Pipe}
import java.net.URI
import io.quartz.websocket.WebSocketFrame

/** Represents an HTTP request.
  *
  * @param headers
  *   the request headers
  * @param stream
  *   a stream of request body bytes
  * @param trailingHeaders
  *   deferred trailing headers
  */
sealed case class Request(
    connId: Long,
    streamId: Int,
    headers: Headers,
    stream: Stream[IO, Byte],
    secure: Boolean,
    sniServerNames: Option[Array[String]],
    trailingHeaders: Deferred[IO, Headers]
) {

  /** Adds the specified headers to the request headers.
    *
    * @param hdr
    *   the headers to add
    * @return
    *   a new request with the additional headers
    */
  def hdr(hdr: Headers): Request =
    new Request(connId, streamId, headers ++ hdr, this.stream, secure, sniServerNames, this.trailingHeaders)

  /** Adds the specified header pair to the request headers.
    *
    * @param pair
    *   a tuple containing the header name and value
    * @return
    *   a new request with the additional header pair
    */
  def hdr(pair: (String, String)): Request =
    new Request(connId, streamId, headers + pair, this.stream, secure, sniServerNames, this.trailingHeaders)

  /** Returns the path component of the request URI.
    *
    * @return
    *   the path component of the request URI
    */
  def path: String = headers.get(":path").getOrElse("")

  /** Returns the HTTP method used in the request.
    *
    * @return
    *   the HTTP method used in the request
    */
  def method: Method = Method(headers.get(":method").getOrElse(""))

  /** Returns the content length of the request body as a string.
    *
    * @return
    *   the content length of the request body as a string
    */
  def contentLen: String = headers.get("content-length").getOrElse("0") // keep it string
  /** Returns the URI of the request.
    *
    * @return
    *   the URI of the request
    */
  def uri: URI = new URI(path)

  /** Returns the content type of the request body.
    *
    * @return
    *   the content type of the request body
    */
  def contentType: ContentType = ContentType(headers.get("content-type").getOrElse(""))

  /** Returns `true` if the content type of the request body is JSON.
    *
    * @return
    *   `true` if the content type of the request body is JSON
    */
  def isJSONBody: Boolean = contentType == ContentType.JSON

  /** Returns the transfer encoding used in the request.
    *
    * @return
    *   the transfer encoding used in the request
    */
  def transferEncoding = headers.getMval("transfer-encoding")

  /** Returns the request body as a byte array.
    *
    * @return
    *   the request body as a byte array
    */
  def body = stream.compile.toVector.map(_.toArray)
}

/** A companion object for the [[Response]] case class.
  */
object Response {

  /** Constructs an HTTP response with a 200 status code and an empty body.
    *
    * @return
    *   a new HTTP response
    */
  def Ok(): Response = {
    val h = Headers() + (":status", StatusCode.OK.toString)
    new Response(StatusCode.OK, h)
  }

  /** Constructs an HTTP response with the specified status code and an empty body.
    *
    * @param code
    *   the status code for the response
    * @return
    *   a new HTTP response
    */
  def Error(code: StatusCode): Response = {
    new Response(code, Headers() + (":status", code.toString))
  }
}

/** Represents an HTTP response, including the response code, headers, and a response body as a `Stream` of bytes.
  * Responses are immutable and can be modified using various helper methods.
  * @param code
  *   the HTTP response code, such as 200 OK or 404 Not Found.
  * @param headers
  *   the HTTP headers associated with the response.
  * @param stream
  *   the response body as a `Stream` of bytes. Defaults to an empty stream.
  */

// IO[Pipe[IO, QuartzH2WebSocketFrame, QuartzH2WebSocketFrame]]
sealed case class Response(
    code: StatusCode,
    headers: Headers,
    stream: Stream[IO, Byte] = Stream.empty,
    websocket: Option[IO[Pipe[IO, WebSocketFrame, WebSocketFrame]]] = None
) {

  /** Adds the given headers to the response headers. */
  def hdr(hdr: Headers): Response = new Response(this.code, this.headers ++ hdr, this.stream)

  /** Adds a single header to the response headers, given as a tuple of (name, value). */
  def hdr(pair: (String, String)) = new Response(this.code, this.headers + pair, this.stream)

  /** Adds a cookie to the response headers. */
  def cookie(cookie: Cookie) = {
    val pair = ("Set-Cookie" -> cookie.toString())
    new Response(this.code, this.headers + pair, this.stream)
  }

  /** Sets the response body to the given `Stream` of bytes. */
  def asStream(s0: Stream[IO, Byte]) =
    new Response(this.code, this.headers, s0)

  def asWebsocketPipe(pipe: IO[Pipe[IO, WebSocketFrame, WebSocketFrame]]) =
    new Response(this.code, this.headers, this.stream, Some(pipe))

  /** Sets the response body to the given text string, encoded as bytes. */
  def asText(text: String) = new Response(this.code, this.headers, Stream.emits(text.getBytes()))

  /** Sets the content type header for the response. */
  def contentType(type0: ContentType): Response =
    new Response(this.code, this.headers + ("content-type" -> type0.toString()), this.stream)

  /** Returns true if the response body is sent using chunked encoding. */
  def isChunked: Boolean = transferEncoding().exists(_.equalsIgnoreCase("chunked"))

  /** Returns the list of transfer encoding values associated with the response. */
  def transferEncoding(): List[String] = headers.getMval("transfer-encoding")

  /** Sets the transfer encoding header for the response. */
  def transferEncoding(vals0: String*): Response =
    new Response(this.code, vals0.foldLeft(this.headers)((h, v) => h + ("transfer-encoding" -> v)), this.stream)
}
