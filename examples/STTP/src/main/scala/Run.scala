package com.example

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
import fs2.{Stream, Chunk}

import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import io.quartz.MyLogger._

import sttp.tapir._
import sttp.tapir.inputStreamRangeBody
import sttp.tapir.generic.auto._
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

import io.quartz.sttp.capabilities.fs2.Fs2IOStreams

case class Device(id: Int, model: String)
case class User(name: String, devices: Seq[Device])

import sttp.model.Part
import sttp.model.MediaType

import sttp.tapir.generic.auto._
import java.io.File

case class MultipartForm(pic: Part[File], bytesText1: Part[Array[Byte]])
object Main extends IOApp {

  given codec: JsonValueCodec[User] = JsonCodecMaker.make

  def run(args: List[String]): IO[ExitCode] = {
    val bytesPart = Part("part1", "TEXT BODY YY-90".getBytes())
    val filePart = Part[java.io.File]("part2", java.io.File("web_root/quartz-h2.jpeg"), Some(MediaType.ImageJpeg))
    val form = MultipartForm(filePart, bytesPart)

    val mpart = endpoint.get
      .in("mpart")
      .out(multipartBody: EndpointIO.Body[Seq[RawPart], MultipartForm]) // Seq[Part[Array[Byte]]]
      .serverLogic(Unit => IO(Right(form)))

    val top = endpoint.get.in("").errorOut(stringBody).out( stringBody ).serverLogic( Unit => IO(Right("ok")))

    val user: Endpoint[Unit, Unit, String, User, Any] =
      endpoint.get.in("user").errorOut(stringBody).out(jsonBody[User])

    val user_post: ServerEndpoint[Any, IO] = endpoint.post
      .in("user")
      .in(jsonBody[User])
      .serverLogic((u: User) => { println(u); IO(Right(())) })

    val ldt: ServerEndpoint[Any, IO] = endpoint.get
      .in("ldt")
      .out(stringBody)
      .serverLogic(Unit => IO(Right(new java.util.Date().toString())))

    val serverEndpoints = List(
      user.serverLogic(Unit => IO(Right(new User("OLAF", Array(new Device(15, "bb")))))),
      user_post,
      ldt,
      top,
      mpart
    )

    val R2 = QuartzH2ServerInterpreter().toRoutes(serverEndpoints)

    for {
      _ <- IO(QuartzH2Server.setLoggingLevel(Level.TRACE))
      ctx <- QuartzH2Server.buildSSLContext("TLS", "keystore.jks", "password")
      exitCode <- new QuartzH2Server("0.0.0.0", 8443, 16000, ctx)
        .start(R2, sync = false)

    } yield (exitCode)
  }
}
