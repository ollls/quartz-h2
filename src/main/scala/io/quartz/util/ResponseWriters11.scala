package io.quartz.util

import cats.effect.IO
import io.quartz.netio._
import io.quartz.http2.model.StatusCode
import io.quartz.http2.model.Response
import io.quartz.http2.model.Headers
import java.nio.ByteBuffer

//import scala.collection.immutable.ListMap
import java.io.File
import fs2.{Stream, Chunk}

object ResponseWriters11 {

  // final val TAG = "zio-nio-tls-http"

  final val CRLF = "\r\n"

  def writeNoBodyResponse(ch: IOChannel, code: StatusCode, close: Boolean) = {
    val response = Response(code, Headers(), Stream.empty);
    writeFullResponse(ch, response, "", close)
  }

  def wrapDirect( array : Array[Byte]) = {
    var directBuffer = ByteBuffer.allocateDirect(array.length);
    directBuffer.put(array);
    directBuffer.flip();
    directBuffer;
}

  ////////////////////////////////////////////////////////////////////////////
  def writeFullResponse(
      c: IOChannel,
      rs: Response,
      msg: String,
      close: Boolean
  ): IO[Int] =
    c.write(wrapDirect(genResponseFromResponse(rs, msg, close).getBytes()))

  def writeResponseMethodNotAllowed(c: IOChannel, allow: String): IO[Int] =
    c.write(wrapDirect(genResponseMethodNotAllowed(allow).getBytes()))

  def writeResponseUnsupportedMediaType(c: IOChannel): IO[Int] =
    c.write(wrapDirect(genResponseUnsupportedMediaType().getBytes()))

  def writeResponseRedirect(c: IOChannel, location: String): IO[Int] =
    c.write(wrapDirect(genResponseRedirect(location).getBytes()))

  /////////////////////////////////////////////////////////////////////////////
  def writeFullResponseFromStreamChunked(
      c: IOChannel,
      rs: Response
  ) = {
    val code = rs.code
    val stream = rs.stream.chunks

    val header = Stream(genResponseChunked(rs, code, false)).map(str => Chunk.array(str.getBytes()))
    // val s0 = stream.map(c => (c.size.toHexString -> c.appended[Byte](('\r')).appended[Byte]('\n'))  )

    val s0 = stream.map(c => (c.size.toHexString -> (c ++ Chunk.array("\r\n".getBytes()))))

    val s1 = s0.map(c => (Chunk.array((c._1 + CRLF).getBytes()) ++ c._2))
    val zs = Stream(Chunk.array(("0".toString + CRLF + CRLF).getBytes))
    val res = header ++ s1 ++ zs

    res
      .foreach { chunk0 =>
        {
           c.write(wrapDirect(chunk0.toArray)).void
        }
      }
      .compile
      .drain
  }

  ///////////////////////////////////////////////////////////////////////
  private def genResponseFromResponse(resp: Response, msg: String, close: Boolean): String = {
    val dfmt = new java.text.SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss")

    dfmt.setTimeZone(java.util.TimeZone.getTimeZone("GMT"))

    val r = new StringBuilder
    val code = resp.code

    r ++= "HTTP/1.1 " + code.value.toString + CRLF
    r ++= "Date: " + dfmt.format(new java.util.Date()) + " GMT" + CRLF
    // r ++= "Server: " + TAG + CRLF
    r ++= "Content-Length: " + msg.length + CRLF

    resp.headers.foreach { case (key, value) => r ++= key + ": " + value + CRLF }

    if (close)
      r ++= "Connection: close" + CRLF
    else
      r ++= "Connection: keep-alive" + CRLF
    r ++= CRLF

    r ++= msg

    r.toString()
  }

  ///////////////////////////////////////////////////////////////////////
  private def genResponseChunked(resp: Response, code: StatusCode, close: Boolean): String = {
    val dfmt = new java.text.SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss")

    dfmt.setTimeZone(java.util.TimeZone.getTimeZone("GMT"))

    val r = new StringBuilder

    r ++= "HTTP/1.1 " + code.value.toString + CRLF
    r ++= "Date: " + dfmt.format(new java.util.Date()) + " GMT" + CRLF
    // r ++= "Server: " + TAG + CRLF
    resp.headers.foreach { case (key, value) =>
      if (key.startsWith(":") == false)
        r ++= key + ": " + value + CRLF
    }

    if (close)
      r ++= "Connection: close" + CRLF
    else
      r ++= "Connection: keep-alive" + CRLF
    r ++= CRLF

    r.toString()
  }

  ///////////////////////////////////////////////////////////////////////
  private def genResponse(code: StatusCode, msg: String, close: Boolean): String = {
    val dfmt = new java.text.SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss")

    dfmt.setTimeZone(java.util.TimeZone.getTimeZone("GMT"))

    val r = new StringBuilder

    r ++= "HTTP/1.1 " + code.value.toString + CRLF
    r ++= "Date: " + dfmt.format(new java.util.Date()) + " GMT" + CRLF
    // r ++= "Server: " + TAG + CRLF
    r ++= "Content-Length: " + msg.length + CRLF
    if (close)
      r ++= "Connection: close" + CRLF
    else
      r ++= "Connection: keep-alive" + CRLF
    r ++= CRLF

    r ++= msg

    r.toString()
  }

  ///////////////////////////////////////////////////////////////////////
  private def genResponseUnsupportedMediaType(): String = {
    val dfmt = new java.text.SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss")

    dfmt.setTimeZone(java.util.TimeZone.getTimeZone("GMT"))

    val r = new StringBuilder

    r ++= "HTTP/1.1 415 Unsupported Media Type" + CRLF
    r ++= "Date: " + dfmt.format(new java.util.Date()) + " GMT" + CRLF
    // r ++= "Server: " + TAG + CRLF
    r ++= "Content-Length: 0" + CRLF
    r ++= "Connection: keep-alive" + CRLF
    r ++= CRLF

    r.toString()
  }

  ////////////////////////////////////////////////////////////////////////
  private def genResponseRedirect(location: String): String = {

    val dfmt = new java.text.SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss")

    dfmt.setTimeZone(java.util.TimeZone.getTimeZone("GMT"))

    val r = new StringBuilder

    r ++= "HTTP/1.1 303 Redirect" + CRLF
    r ++= "Location: " + location + CRLF
    r ++= "Cache-Control: no-cache, no-store, must-revalidate" + CRLF
    r ++= "Date: " + dfmt.format(new java.util.Date()) + " GMT" + CRLF
    // r ++= "Server: " + TAG + CRLF
    r ++= "Content-Length: 0" + CRLF
    r ++= "Connection: keep-alive" + CRLF
    r ++= CRLF

    r.toString()

  }

  ///////////////////////////////////////////////////////////////////////
  // example: Allow: GET, POST, HEAD
  private def genResponseMethodNotAllowed(allow: String): String = {
    val dfmt = new java.text.SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss")

    dfmt.setTimeZone(java.util.TimeZone.getTimeZone("GMT"))

    val r = new StringBuilder

    r ++= "HTTP/1.1 405 Method not allowed" + CRLF
    r ++= "Date: " + dfmt.format(new java.util.Date()) + " GMT" + CRLF
    // r ++= "Server: " + TAG + CRLF
    r ++= "Allow: " + allow + CRLF
    r ++= "Content-Length: 0" + CRLF
    r ++= "Connection: keep-alive" + CRLF
    r ++= CRLF

    r.toString()
  }

  //////////////////////////////////////////////////////////////////////////////
  def genResponseContentTypeFileHeader(fpath: String, cont_type: String): String = {
    val dfmt = new java.text.SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss")

    dfmt.setTimeZone(java.util.TimeZone.getTimeZone("GMT"))

    val fp = new File(fpath)
    val file_size = fp.length()

    val r = new StringBuilder

    r ++= "HTTP/1.1 200 OK" + CRLF
    r ++= "Date: " + dfmt.format(new java.util.Date()) + " GMT" + CRLF
    // r ++= "Server: " + TAG + CRLF
    r ++= "Content-Type: " + cont_type + CRLF
    r ++= "Content-Length: " + file_size + CRLF
    r ++= "Connection: keep-alive" + CRLF
    r ++= CRLF

    r.toString()

  }

}
