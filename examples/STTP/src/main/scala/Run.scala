package com.example

import scala.util.Try
import cats.effect.{IO, IOApp, ExitCode}
import cats.MonadError
import cats.data.{OptionT}
import io.quartz.QuartzH2Server
import io.quartz.http2.routes.{HttpRouteIO, Routes}
import io.quartz.http2.model.{Headers, Method, ContentType, Request, Response}
import io.quartz.http2._
import io.quartz.http2.model.Method._
import io.quartz.http2.model.ContentType.JSON
import io.quartz.http2.model.Request
import io.quartz.http2.model.Response

import ch.qos.logback.classic.Level

import com.github.plokhotnyuk.jsoniter_scala.macros._
import com.github.plokhotnyuk.jsoniter_scala.core._
import fs2.{Stream, Chunk, Pipe}

import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import io.quartz.MyLogger._

import sttp.tapir._

import sttp.tapir.inputStreamRangeBody
import sttp.tapir.generic.auto._
import sttp.tapir.RangeValue
import sttp.tapir.json.jsoniter.jsonBody
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.RequestResult
import io.quartz.http2.model.StatusCode
import sttp.tapir.integ.cats.effect.CatsMonadError
import io.quartz.sttp.QuartzH2BodyListener
import sttp.tapir.server.interceptor.reject.RejectInterceptor

import sttp.tapir.server.interpreter.{BodyListener, FilterServerEndpoints, ServerInterpreter}

import io.quartz.sttp.QuartzH2RequestBody
import io.quartz.sttp.QuartzH2Request
import io.quartz.sttp.capabilities.fs2.Fs2IOStreams
import io.quartz.sttp.QuartzH2ToResponseBody
import io.quartz.sttp.QuartzH2ResponseBody
import io.quartz.sttp.QuartzH2ServerOptions
import io.quartz.sttp.QuartzH2ServerInterpreter
import java.util.Date
import sttp.tapir.InputStreamRange

import sttp.tapir.EndpointIO.annotations.fileBody
import sttp.model.HeaderNames

case class Device(id: Int, model: String)
case class User(name: String, devices: Seq[Device])

import sttp.model.Part
import sttp.model.MediaType

import sttp.tapir.generic.auto._
import java.io.File

import sttp.capabilities.Streams
import io.quartz.sttp.capabilities.fs2.Fs2IOStreams

val VIDEO_FILE = "web_root/mov_bbb.mp4"
case class MultipartForm(pic: Part[File], bytesText1: Part[Array[Byte]])
case class ResponseWS(my_websocket_msg: String, count: Int)
given codec: JsonValueCodec[User] = JsonCodecMaker.make
given codecWS: JsonValueCodec[ResponseWS] = JsonCodecMaker.make

////////////////////////////////////////////////////////////
def httpRangedVideoEndPoint: ServerEndpoint[Any, IO] = {
  val video2 = endpoint.get
    .in("mp4safari")
    .in(header[Option[String]](HeaderNames.Range))
    .out(header[String]("Accept-Ranges"))
    .out(header[Option[String]]("Content-Range"))
    .out(statusCode)
    .out(inputStreamRangeBody)
    .out(header(sttp.model.Header.contentType(MediaType("video", "mp4", None, Map.empty))))

  val video_play2 = video2.serverLogicSuccess((range: Option[String]) => {
    val file = java.io.File(VIDEO_FILE)
    val rangeValue = for {
      rR <- range
      rangeMinMax <- Some(rR.split("=")(1).split("-"))
    } yield (RangeValue(Try(rangeMinMax(0).toLong).toOption, Try(rangeMinMax(1).toLong).toOption, file.length()))
    val fis = new java.io.FileInputStream(file);
    rangeValue match {
      case Some(rv) =>
        IO(
          (
            "bytes",
            Some(s"bytes ${rv.start.getOrElse("")}-${rv.end.getOrElse("")}/${file.length()}"),
            sttp.model.StatusCode.unsafeApply(206),
            sttp.tapir.InputStreamRange(() => fis, rangeValue)
          )
        )
      case None =>
        IO(
          "bytes",
          None,
          sttp.model.StatusCode.Ok,
          sttp.tapir.InputStreamRange(() => fis, Some(RangeValue(Some(0L), Some(0L), file.length)))
        )
    }
  })

  video_play2
}

///////////////////////////////////////////////////////////
def fileRetrievalAsMp4: ServerEndpoint[Any, IO] = {
  val video = endpoint.get
    .in("mp4")
    .out(streamBody(Fs2IOStreams())(Schema.binary, CodecFormat.OctetStream()))
    .out(header(sttp.model.Header.contentType(MediaType("video", "mp4", None, Map.empty))))

  val video_play: ServerEndpoint[Fs2IOStreams, IO] = video.serverLogicSuccess[IO](Unit => {
    val fis = new java.io.FileInputStream(java.io.File(VIDEO_FILE))
    val s0 = fs2.io.readInputStream[IO](IO(fis), 1024 * 16, true)
    IO(s0)

  })
  // todo: how to properly adjust to [Any, IO]
  video_play.asInstanceOf[ServerEndpoint[Any, cats.effect.IO]]
}

/////////////////////////////////////////////////////////////////
def multiPart = {

  val bytesPart = Part("part1", "TEXT BODY YY-90".getBytes())
  val filePart = Part[java.io.File]("part2", java.io.File("web_root/quartz-h2.jpeg"), Some(MediaType.ImageJpeg))
  val form = MultipartForm(filePart, bytesPart)

  val mpart = endpoint.get
    .in("mpart")
    .out(multipartBody: EndpointIO.Body[Seq[RawPart], MultipartForm]) // Seq[Part[Array[Byte]]]
    .serverLogic(Unit => IO(Right(form)))

  mpart

}

def jsonGet = {
  val user: Endpoint[Unit, Unit, String, User, Any] =
    endpoint.get.in("user").errorOut(stringBody).out(jsonBody[User])
  user.serverLogic(Unit => IO(Right(new User("OLAF", Array(new Device(15, "bb"))))))
}

def jsonPost = {

  val user_post: ServerEndpoint[Any, IO] = endpoint.post
    .in("user")
    .in(jsonBody[User])
    .serverLogic((u: User) => { println(u); IO(Right(())) })

  user_post
}

import sttp.tapir.Codec.binaryWebSocketFrame
import sttp.tapir.json.jsoniter.jsoniterCodec

object Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {

    val wsPipe: Pipe[IO, String, ResponseWS] = requestStream =>
      requestStream.map(text => {
        ResponseWS(text, 1)
      })

    val wse = endpoint.get
      .in("ws")
      .out(webSocketBody[String, CodecFormat.TextPlain, ResponseWS, CodecFormat.Json](Fs2IOStreams()))

    val wseL = wse.serverLogicSuccess[IO](data => IO(wsPipe))

    val top = endpoint.get.in("").errorOut(stringBody).out(stringBody).serverLogic(Unit => IO(Right("ok")))

    val ldt: ServerEndpoint[Any, IO] = endpoint.get
      .in("ldt")
      .out(stringBody)
      .serverLogic(Unit => IO(Right(new java.util.Date().toString())))

    val serverEndpoints = List(
      top,
      ldt,
      jsonGet,
      jsonPost,
      multiPart,
      httpRangedVideoEndPoint,
      fileRetrievalAsMp4,
      wseL
    )

    val R2 = QuartzH2ServerInterpreter().toRoutes(serverEndpoints)

    for {
      _ <- IO(QuartzH2Server.setLoggingLevel(Level.INFO))
      ctx <- QuartzH2Server.buildSSLContext("TLS", "keystore.jks", "password")
      exitCode <- new QuartzH2Server("localhost", 8443, 16000, Some(ctx)) // use 0.0.0.0 for non-local exposure
        .start(R2, sync = false)

    } yield (exitCode)
  }
}
