package io.quartz.sttp

import cats.effect.IO
import cats.syntax.all._
import fs2.Chunk
import fs2.io.file.Files
import io.quartz.sttp.capabilities.fs2._
import sttp.model.{Header, Part}
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.interpreter.{RawValue, RequestBody}
import sttp.tapir.{FileRange, InputStreamRange, RawBodyType, RawPart}
import io.quartz.http2.model.Request

import java.io.ByteArrayInputStream

class QuartzH2RequestBody(serverOptions: QuartzH2ServerOptions[IO])
    extends RequestBody[IO, Fs2IOStreams] {
  override val streams: Fs2IOStreams = Fs2IOStreams()

  override def toRaw[R](
      serverRequest: ServerRequest,
      bodyType: RawBodyType[R],
      maxBytes: Option[Long]
  ): IO[RawValue[R]] = {
    toRawFromStream(
      serverRequest,
      toStream(serverRequest, maxBytes),
      bodyType,
      // quartzH2Request(serverRequest).charset,
      maxBytes
    )
  }

  override def toStream(
      serverRequest: ServerRequest,
      maxBytes: Option[Long]
  ): streams.BinaryStream = {
    val stream = quartzH2Request(serverRequest).stream
    maxBytes.map(Fs2IOStreams.limitBytes(stream, _)).getOrElse(stream)
  }

  // val test = serverRequest.underlying

  private def quartzH2Request(serverRequest: ServerRequest) =
    serverRequest.underlying.asInstanceOf[io.quartz.http2.model.Request]

  private def toRawFromStream[R](
      serverRequest: ServerRequest,
      body: fs2.Stream[IO, Byte],
      bodyType: RawBodyType[R],
      // charset: Option[Charset],
      maxBytes: Option[Long]
  ): IO[RawValue[R]] = {
    def asChunk: IO[Chunk[Byte]] = body.compile.to(Chunk)
    def asByteArray: IO[Array[Byte]] =
      body.compile.to(Chunk).map(_.toArray[Byte])

    bodyType match {
      case RawBodyType.StringBody(defaultCharset) =>
        asByteArray
          .map(
            // new String(_, charset.map(_.nioCharset).getOrElse(defaultCharset))
            new String(_, defaultCharset)
          )
          .map(RawValue(_))
      case RawBodyType.ByteArrayBody => asByteArray.map(RawValue(_))
      case RawBodyType.ByteBufferBody =>
        asChunk.map(c => RawValue(c.toByteBuffer))
      case RawBodyType.InputStreamBody =>
        asByteArray.map(b => RawValue(new ByteArrayInputStream(b)))
      case RawBodyType.InputStreamRangeBody =>
        asByteArray.map(b =>
          RawValue(InputStreamRange(() => new ByteArrayInputStream(b)))
        )

      case RawBodyType.FileBody =>
        serverOptions.createFile(serverRequest).flatMap { file =>
          val fileSink = Files[IO].writeAll(file.toPath)
          body
            .through(fileSink)
            .compile
            .drain
            .map(_ => RawValue(FileRange(file), Seq(FileRange(file))))
        }
       case m: RawBodyType.MultipartBody => ???  

      /*
      case m: RawBodyType.MultipartBody =>
        // TODO: use MultipartDecoder.mixedMultipart once available?
        implicitly[EntityDecoder[F, multipart.Multipart[F]]]
          .decode(limitedMedia(http4sRequest(serverRequest), maxBytes), strict = false)
          .value
          .flatMap {
            case Left(failure) => Sync[F].raiseError(failure)
            case Right(mp) =>
              val rawPartsF: Vector[F[RawPart]] = mp.parts
                .flatMap(part => part.name.flatMap(name => m.partType(name)).map((part, _)).toList)
                .map { case (part, codecMeta) => toRawPart(serverRequest, part, codecMeta).asInstanceOf[F[RawPart]] }

              val rawParts: F[RawValue[Vector[RawPart]]] = rawPartsF.sequence.map { parts =>
                RawValue(parts, parts collect { case _ @Part(_, f: FileRange, _, _) => f })
              }

              rawParts.asInstanceOf[F[RawValue[R]]] // R is Vector[RawPart]
          }
    } */
    }
  }

  /*
  private def limitedMedia(media: Media[F], maxBytes: Option[Long]): Media[F] =
    maxBytes
      .map(limit =>
        new Media[F] {
          override def body: fs2.Stream[F, Byte] =
            Fs2IOStreams.limitBytes(media.body, limit)
          override def headers: Headers = media.headers
          override def covary[F2[x] >: F[x]]: Media[F2] = media.covary
        }
      )
      .getOrElse(media)*/

  /*
  private def toRawPart[R](
      serverRequest: ServerRequest,
      part: multipart.Part[F],
      partType: RawBodyType[R]
  ): F[Part[R]] = {
    val dispositionParams = part.headers
      .get[`Content-Disposition`]
      .map(_.parameters)
      .getOrElse(Map.empty)
    val charset = part.headers.get[`Content-Type`].flatMap(_.charset)
    toRawFromStream(
      serverRequest,
      part.body,
      partType,
      charset,
      maxBytes = None
    )
      .map(r =>
        Part(
          part.name.getOrElse(""),
          r.value,
          otherDispositionParams = dispositionParams.map { case (k, v) =>
            k.toString -> v
          } - Part.NameDispositionParam,
          headers =
            part.headers.headers.map(h => Header(h.name.toString, h.value))
        )
      )
  }*/
}
