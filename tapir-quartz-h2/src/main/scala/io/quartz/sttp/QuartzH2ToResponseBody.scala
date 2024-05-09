package io.quartz.sttp

import cats.effect.IO
import cats.syntax.all._
import fs2.io.file.Files
import fs2.{Chunk, Stream, Pipe}

import io.quartz.sttp.capabilities.fs2.Fs2IOStreams
import sttp.model.{HasHeaders, HeaderNames, Part}
import sttp.tapir.server.interpreter.ToResponseBody
import sttp.tapir.{CodecFormat, RawBodyType, RawPart, WebSocketBodyOutput}
import io.quartz.websocket.{WebSocketFrame => QuartzH2WebSocketFrame}

import java.io.InputStream
import java.nio.charset.Charset

type QuartzH2ResponseBody = Either[
  IO[Pipe[IO, QuartzH2WebSocketFrame, QuartzH2WebSocketFrame]],
  (fs2.Stream[IO, Byte], Option[String])
]

class QuartzH2ToResponseBody(serverOptions: QuartzH2ServerOptions[IO])
    extends ToResponseBody[QuartzH2ResponseBody, Fs2IOStreams] {

  val CRLF: String = "\r\n";

  /** The pool of ASCII chars to be used for generating a multipart boundary.
    */
  private val MULTIPART_CHARS =
    // "-_1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    "1234567890".toCharArray(); // some clients only recognize digits. (firefox does digits)

  private def generateBoundary(): String = {
    val rand = java.util.concurrent.ThreadLocalRandom.current()
    val count = rand.nextInt(30, 41); // a random size from 30 to 40
    val buffer = java.nio.CharBuffer.allocate(count);
    while (buffer.hasRemaining()) {
      buffer.put(MULTIPART_CHARS(rand.nextInt(MULTIPART_CHARS.length)))
    }
    buffer.flip()
    buffer.toString()
  }

  override val streams: Fs2IOStreams = Fs2IOStreams()

  private def rawValueToEntity[CF <: CodecFormat, R](
      bodyType: RawBodyType[R],
      v: R
  ): (fs2.Stream[IO, Byte], Option[String]) =
    bodyType match {

      case RawBodyType.StringBody(charset) =>
        val bytes = v.toString.getBytes(charset)
        (fs2.Stream.chunk(Chunk.array(bytes)), None)

      case RawBodyType.ByteArrayBody =>
        (fs2.Stream.chunk(Chunk.array(v)), None)

      case RawBodyType.ByteBufferBody =>
        (fs2.Stream.chunk(Chunk.byteBuffer(v)), None)

      case RawBodyType.InputStreamBody =>
        (fs2.io.readInputStream(IO.blocking(v), serverOptions.ioChunkSize), None)

      case RawBodyType.InputStreamRangeBody =>
        (
          v.range
            .map(range => inputStreamToFs2(v.inputStreamFromRangeStart).take(range.contentLength))
            .getOrElse(inputStreamToFs2(v.inputStream)),
          None
        )

      case RawBodyType.FileBody =>
        val tapirFile = v
        val stream = tapirFile.range
          .flatMap(r =>
            r.startAndEnd.map(s =>
              Files[IO].readRange(
                tapirFile.file.toPath,
                r.contentLength.toInt,
                s._1,
                s._2
              )
            )
          )
          .getOrElse(
            Files[IO].readAll(tapirFile.file.toPath, serverOptions.ioChunkSize)
          )
        (stream, None)

      // an attempt to provide minimal support without using Apache mime libs
      case m: RawBodyType.MultipartBody =>
        val boundary = generateBoundary()
        val parts = (v: Seq[RawPart])

        var output: fs2.Stream[IO, Byte] = Stream.empty

        val test = parts.foreach(part => {

          output = output ++ fs2.Stream.chunk(Chunk.array((CRLF + "--" + boundary + CRLF).getBytes()))

          output = output ++ fs2.Stream.chunk(
            Chunk.array((s"Content-Disposition: ${part.contentDispositionHeaderValue}" + CRLF).getBytes())
          )
          val headers = part.headers.foreach { header =>
            output =
              output ++ fs2.Stream.chunk(Chunk.array((header.name + ": " + header.value + CRLF + CRLF).getBytes()))
          }
          m.partType(part.name)
            .foreach(partType =>
              output = output ++ rawValueToEntity(partType.asInstanceOf[RawBodyType[Any]], part.body)._1
            )
        })
        output = output ++ fs2.Stream.chunk(Chunk.array((CRLF + "--" + boundary + "--" + CRLF).getBytes()))
        (
          output.chunkMin(serverOptions.ioChunkSize, allowFewerTotal = true).flatMap(c => Stream.chunk(c)),
          Some(boundary)
        )
    }

  override def fromRawValue[R](
      r: R,
      headers: HasHeaders,
      format: CodecFormat,
      bodyType: RawBodyType[R]
  ): QuartzH2ResponseBody = {
    Right(rawValueToEntity(bodyType, r))
  }

  override def fromStreamValue(
      v: Stream[IO, Byte],
      headers: HasHeaders,
      format: CodecFormat,
      charset: Option[Charset]
  ): QuartzH2ResponseBody = Right(v, None)

  override def fromWebSocketPipe[REQ, RESP](
      pipe: streams.Pipe[REQ, RESP],
      o: WebSocketBodyOutput[streams.Pipe[REQ, RESP], REQ, RESP, _, Fs2IOStreams]
  ): QuartzH2ResponseBody = Left(QuartzH2WebSockets.pipeToBody(pipe, o))

  private def inputStreamToFs2(inputStream: () => InputStream) =
    fs2.io.readInputStream(
      IO.blocking(inputStream()),
      serverOptions.ioChunkSize
    )

}
