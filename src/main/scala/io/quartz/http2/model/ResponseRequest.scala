package io.quartz.http2.model

import cats.effect.{IO, IOApp, ExitCode, Deferred}
import fs2.{Stream, Chunk}
import java.net.URI

sealed case class Request(headers: Headers, stream: Stream[IO, Byte], trailingHeaders: Deferred[IO, Headers]) {
  def hdr(hdr: Headers): Request = new Request(headers ++ hdr, this.stream, this.trailingHeaders)
  def hdr(pair: (String, String)): Request = new Request(headers + pair, this.stream, this.trailingHeaders)
  def path: String = headers.get(":path").getOrElse("")
  def method: Method = Method(headers.get(":method").getOrElse(""))
  def contentLen: String = headers.get("content-length").getOrElse("0") // keep it string
  def uri: URI = new URI(path)
  def contentType: ContentType = ContentType(headers.get("content-type").getOrElse(""))
  def isJSONBody: Boolean = contentType == ContentType.JSON
  def transferEncoding = headers.getMval("transfer-encoding")
  def body = stream.compile.toVector.map(_.toArray)
}

object Response {

  def Ok(): Response = {
    val h = Headers() + (":status", StatusCode.OK.toString)
    new Response(StatusCode.OK, h)
  }

  def Error(code: StatusCode): Response = {
    new Response(code, Headers() + (":status", code.toString))
  }

}

//Response ///////////////////////////////////////////////////////////////////////////
sealed case class Response(
    code: StatusCode,
    headers: Headers,
    stream: Stream[IO, Byte] = Stream.empty
) {
  def hdr(hdr: Headers): Response = new Response(this.code, this.headers ++ hdr, this.stream)
  def hdr(pair: (String, String)) = new Response(this.code, this.headers + pair, this.stream)
  def cookie(cookie: Cookie) = {
    val pair = ("Set-Cookie" -> cookie.toString())
    new Response(this.code, this.headers + pair, this.stream)
  }
  def asStream(s0: Stream[IO, Byte]) =
    new Response(this.code, this.headers, s0)
  def asText(text: String) = new Response(this.code, this.headers, Stream.emits(text.getBytes()))
  def contentType(type0: ContentType): Response =
    new Response(this.code, this.headers + ("content-type" -> type0.toString()), this.stream)
  def isChunked: Boolean = transferEncoding().exists(_.equalsIgnoreCase("chunked"))
  def transferEncoding(): List[String] = headers.getMval("transfer-encoding")
  def transferEncoding(vals0: String*): Response =
    new Response(this.code, vals0.foldLeft(this.headers)((h, v) => h + ("transfer-encoding" -> v)), this.stream)
}
