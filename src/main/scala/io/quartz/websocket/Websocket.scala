package io.quartz.websocket

import cats.effect.IO
import fs2.{Chunk, Stream, Pull}

import java.security.MessageDigest
import java.util.Base64
import java.nio.ByteBuffer
import io.quartz.netio._

import io.quartz.http2.model.{Response, Request, Headers}
import org.typelevel.log4cats.Logger
import ch.qos.logback.classic.Level
import io.quartz.MyLogger._

object Websocket {

  val WS_PACKET_SZ = 32768

  private val magicString =
    "258EAFA5-E914-47DA-95CA-C5AB0DC85B11".getBytes("US-ASCII")
  def apply(isClient: Boolean = false, idleTimeout: Int = 0) = new Websocket(isClient, idleTimeout)

}

class Websocket(isClient: Boolean, idleTimeout: Int) {

  final val CRLF = "\r\n"
  var isClosed = true

  // private val IN_J_BUFFER = java.nio.ByteBuffer.allocate(0xffff * 2) // 64KB * 2

  private val frames = new FrameTranscoder(isClient)

  def closeReply(c: IOChannel) = {
    val T = frames.frameToBuffer(WebSocketFrame.Close())
    val chunks = Chunk.array(T(0).array()) ++ Chunk.array(T(1).array())
    c.write(ByteBuffer.wrap(chunks.toArray))
  }

  def pongReply(c: IOChannel, data: Chunk[Byte] = Chunk.empty) = {
    val T = frames.frameToBuffer(WebSocketFrame.Pong(data))
    c.write(ByteBuffer.wrap(T(0).array()))
  }

  def pingReply(c: IOChannel, data: Chunk[Byte] = Chunk.empty) = {
    val T = frames.frameToBuffer(WebSocketFrame.Ping(data))
    c.write(ByteBuffer.wrap(T(0).array()))
  }

  def writeBinary(c: IOChannel, data: Chunk[Byte], last: Boolean = true) = {
    val frame = WebSocketFrame.Binary(data, last)
    writeFrame(c, frame)
  }

  def writeText(c: IOChannel, str: String, last: Boolean = true) = {
    val frame = WebSocketFrame.Text(str, last)
    writeFrame(c, frame)
  }

  /////////////////////////////////////////////////////////////////////
  private def genWsResponse(resp: Response): String = {
    val r = new StringBuilder
    r ++= "HTTP/1.1 101 Switching Protocols" + CRLF
    resp.headers.foreach { case (key, value) => r ++= key + ": " + value + CRLF }
    r ++= CRLF
    val T = r.toString()
    T
  }

  private def genAcceptKey(str: String): String = {
    val crypt = MessageDigest.getInstance("SHA-1")
    crypt.reset()
    crypt.update(str.getBytes("US-ASCII"))
    crypt.update(Websocket.magicString)
    val bytes = crypt.digest()
    Base64.getEncoder.encodeToString(bytes)
  }

  // Not used, websocket client support not implemented yet
  def startClientHadshake(host: String) = {
    val key = {
      val bytes = new Array[Byte](16)
      scala.util.Random.nextBytes(bytes)
      Base64.getEncoder.encodeToString(bytes)
    }

    Response
      .Ok()
      .hdr("host" -> host)
      .hdr("upgrade" -> "websocket")
      .hdr("connection" -> "Upgrade")
      .hdr("sec-websocket-version" -> "13")
      .hdr("sec-websocket-key" -> key)

  }

  private def serverHandshake(req: Request) = {
    val result = for {
      uval <- req.headers.get("upgrade")
      cval <- req.headers.get("connection")
      aval <- req.headers.get("sec-websocket-version")
      kval <- req.headers.get("sec-websocket-key")
      _ <- Option(
        uval.equalsIgnoreCase("websocket") &&
          cval.toLowerCase().contains("upgrade") &&
          aval.equalsIgnoreCase("13") &&
          Base64.getDecoder.decode(kval).length == 16
      ).collect { case true => true } // convert false to None
      rspKey <- Some(genAcceptKey(kval))

    } yield (rspKey)

    val zresp = result.map(key => {
      Response.Ok().hdr("upgrade" -> "websocket").hdr("connection" -> "upgrade").hdr("sec-websocket-accept" -> key)
    }) match {
      case None =>
        Left(
          new Exception(
            "Invalid websocket upgrade request: " +
              "websocket headers or version is invalid"
          )
        )
      case Some(v) => Right(v)
    }
    zresp
  }

  def writeFrame(c: IOChannel, frame: WebSocketFrame) = {
    def processArray(ab: Array[ByteBuffer], i: Int): IO[Int] =
      if (i < ab.length)
        c.write(ByteBuffer.wrap(ab(i).array)) *> processArray(ab, i + 1)
      else IO(0)

    for {
      array <- IO.delay(frames.frameToBuffer(frame))
      _ <- processArray(array, 0)

    } yield ()
  }

  def readFrame(c: IOChannel): IO[WebSocketFrame] = {
    val T = for {
      // _     <- Channel.readBuffer(req.ch, IN_J_BUFFER)
      chunk <- c.read(idleTimeout)
      bbuf <- IO(ByteBuffer.wrap(chunk.toArray))
      frame <- IO(frames.bufferToFrame(bbuf))
      _ <-
        if (frame.opcode == WebSocketFrame.PING) pongReply(c)
        else IO.unit
    } yield (frame)

    (T.iterateWhile(_.opcode == WebSocketFrame.PING))
  }

  def accept(c: IOChannel, req: Request): IO[Unit] = {
    val T = for {
      res <- IO(serverHandshake(req))
      _ <- res match {
        case Right(response) =>
          c.remoteAddress()
            .flatMap(adr =>
              Logger[IO].info(
                "Webocket request initiated from: " + adr.asInstanceOf[java.net.InetSocketAddress].getHostString()
              )
            ) *> c.write(ByteBuffer.wrap(genWsResponse(response).getBytes())) *> IO {
            isClosed = false
          }
        case Left(exception) => IO.raiseError(exception)
      }

    } yield ()

    T
  }

  /////////////////////////////////////////////////////////////
  def makeFrameStream(in: Stream[IO, Byte]) = {

    def go(s: Stream[IO, Byte], leftover: Chunk[Byte]): Pull[IO, WebSocketFrame, Unit] = {
      s.pull.uncons.flatMap {
        case Some((hd1, tl)) =>
          val hd = leftover ++ hd1
          val bb = hd.toByteBuffer
          val f0: WebSocketFrame = frames.bufferToFrame(bb)
          if (f0 == null) {
            go(tl, hd)
          } else {
            // bb may have data remaning, preserve them as leftover
            Pull.output[IO, WebSocketFrame](Chunk.singleton(f0)) >> go(tl, Chunk.byteBuffer(bb))
          }
        case None =>
          Pull.done
      }
    }
    go(in, Chunk.empty[Byte]).stream
  }

  private def doPingPong(c: IOChannel, f0: WebSocketFrame) =
    f0 match {
      case WebSocketFrame.Ping(data) => pongReply(c, data)
      case _                         => IO(0)
    }
}
