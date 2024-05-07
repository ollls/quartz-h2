package io.quartz.sttp

import cats.effect.IO
import io.quartz.sttp.capabilities.fs2.Fs2IOStreams
import io.quartz.websocket.{WebSocketFrame => QuartzH2WebSocketFrame}

import cats.syntax.all._
import fs2._
import fs2.concurrent.Channel

import sttp.tapir.model.WebSocketFrameDecodeFailure
import sttp.tapir.{DecodeResult, WebSocketBodyOutput}
import sttp.ws.WebSocketFrame
import cats.effect.implicits._

object QuartzH2WebSockets {

  def pipeToBody[REQ, RESP](
      pipe: Pipe[IO, REQ, RESP],
      o: WebSocketBodyOutput[Pipe[IO, REQ, RESP], REQ, RESP, _, Fs2IOStreams]
  ): IO[Pipe[IO, QuartzH2WebSocketFrame, QuartzH2WebSocketFrame]] = {

    if ((!o.autoPongOnPing) && o.autoPing.isEmpty) IO.delay {
      // fast track: lift Http4sWebSocketFrames into REQ, run through pipe, convert RESP back to Http4sWebSocketFrame

      (in: Stream[IO, QuartzH2WebSocketFrame]) =>
        {
          val decodeClose = optionallyDecodeClose(in, o.decodeCloseRequests)
          val sttpFrames = decodeClose.map(quartzH2FrameToFrame)
          val concatenated = optionallyConcatenateFrames(sttpFrames, o.concatenateFragmentedFrames)
          val ignorePongs = optionallyIgnorePong(concatenated, o.ignorePong)
          ignorePongs
            .map { f =>
              o.requests.decode(f) match {
                case x: DecodeResult.Value[REQ]    => x.v
                case failure: DecodeResult.Failure => throw new WebSocketFrameDecodeFailure(f, failure)
              }
            }
            .through(pipe)
            .mapChunks(_.map(r => frameToQuartzH2Frame(o.responses.encode(r))))
            .append(Stream(frameToQuartzH2Frame(WebSocketFrame.close)))
        }
    }
    else {
      // concurrently merge business logic response, autoPings, autoPongOnPing
      // use fs2.Channel to perform the merge (more efficient than Stream#mergeHaltL / Stream#parJoin)

      Channel
        .bounded[IO, Chunk[QuartzH2WebSocketFrame]](64)
        .map { c => (in: Stream[IO, QuartzH2WebSocketFrame]) =>
          {
            val decodeClose = optionallyDecodeClose(in, o.decodeCloseRequests)
            val sttpFrames = decodeClose.map(quartzH2FrameToFrame)
            val concatenated = optionallyConcatenateFrames(sttpFrames, o.concatenateFragmentedFrames)
            val ignorePongs = optionallyIgnorePong(concatenated, o.ignorePong)
            val autoPongs = optionallyAutoPong(ignorePongs, c, o.autoPongOnPing)
            val autoPings = o.autoPing match {
              case Some((interval, frame)) =>
                (c.send(Chunk.singleton(frameToQuartzH2Frame(frame))) >> IO.sleep(interval)).foreverM
              case None => IO.unit
            }

            val outputProducer = autoPongs
              .map { f =>
                o.requests.decode(f) match {
                  case x: DecodeResult.Value[REQ]    => x.v
                  case failure: DecodeResult.Failure => throw new WebSocketFrameDecodeFailure(f, failure)
                }
              }
              .through(pipe)
              .chunks
              .foreach(chunk => c.send(chunk.map(r => frameToQuartzH2Frame(o.responses.encode(r)))).void)
              .compile
              .drain

            val outcomes = (outputProducer.guarantee(c.close.void), autoPings).parTupled.void

            Stream
              .bracket(outcomes.start)(f => f.cancel >> f.joinWithUnit) >>
              c.stream.append(Stream(Chunk.singleton(frameToQuartzH2Frame(WebSocketFrame.close)))).unchunks
          }
        }
    }
  }

  private def quartzH2FrameToFrame(f: QuartzH2WebSocketFrame): WebSocketFrame =
    f match {
      case t: QuartzH2WebSocketFrame.Text  => WebSocketFrame.Text(t.str, t.last, None)
      case x: QuartzH2WebSocketFrame.Ping  => WebSocketFrame.Ping(x.data.toArray)
      case x: QuartzH2WebSocketFrame.Pong  => WebSocketFrame.Pong(x.data.toArray)
      case c: QuartzH2WebSocketFrame.Close => WebSocketFrame.Close(c.closeCode, "")
      case _                               => WebSocketFrame.Binary(f.data.toArray, f.last, None)
    }

  private def frameToQuartzH2Frame(w: WebSocketFrame): QuartzH2WebSocketFrame =
    w match {
      case x: WebSocketFrame.Text   => QuartzH2WebSocketFrame.Text(x.payload, x.finalFragment)
      case x: WebSocketFrame.Binary => QuartzH2WebSocketFrame.Binary(fs2.Chunk.array(x.payload), x.finalFragment)
      case x: WebSocketFrame.Ping   => QuartzH2WebSocketFrame.Ping(fs2.Chunk.array(x.payload))
      case x: WebSocketFrame.Pong   => QuartzH2WebSocketFrame.Pong(fs2.Chunk.array(x.payload))
      case x: WebSocketFrame.Close  => QuartzH2WebSocketFrame.Close(x.statusCode, x.reasonText).fold(throw _, identity)
    }

  private def optionallyConcatenateFrames(
      s: Stream[IO, WebSocketFrame],
      doConcatenate: Boolean
  ): Stream[IO, WebSocketFrame] =
    if (doConcatenate) {
      type Accumulator = Option[Either[Array[Byte], String]]

      s.mapAccumulate(None: Accumulator) {
        case (None, f: WebSocketFrame.Ping)                       => (None, Some(f))
        case (None, f: WebSocketFrame.Pong)                       => (None, Some(f))
        case (None, f: WebSocketFrame.Close)                      => (None, Some(f))
        case (None, f: WebSocketFrame.Data[_]) if f.finalFragment => (None, Some(f))
        case (Some(Left(acc)), f: WebSocketFrame.Binary) if f.finalFragment =>
          (None, Some(f.copy(payload = acc ++ f.payload)))
        case (Some(Left(acc)), f: WebSocketFrame.Binary) if !f.finalFragment => (Some(Left(acc ++ f.payload)), None)
        case (Some(Right(acc)), f: WebSocketFrame.Text) if f.finalFragment =>
          (None, Some(f.copy(payload = acc + f.payload)))
        case (Some(Right(acc)), f: WebSocketFrame.Text) if !f.finalFragment => (Some(Right(acc + f.payload)), None)
        case (acc, f) =>
          throw new IllegalStateException(s"Cannot accumulate web socket frames. Accumulator: $acc, frame: $f.")
      }.collect { case (_, Some(f)) => f }
    } else s

  private def optionallyIgnorePong(s: Stream[IO, WebSocketFrame], doIgnore: Boolean): Stream[IO, WebSocketFrame] = {
    if (doIgnore) {
      s.filter {
        case _: WebSocketFrame.Pong => false
        case _                      => true
      }
    } else s
  }

  private def optionallyAutoPong(
      s: Stream[IO, WebSocketFrame],
      c: Channel[IO, Chunk[QuartzH2WebSocketFrame]],
      doAuto: Boolean
  ): Stream[IO, WebSocketFrame] =
    if (doAuto) {
      val trueF = true.pure[IO]
      s.evalFilter {
        case ping: WebSocketFrame.Ping =>
          c.send(Chunk.singleton(frameToQuartzH2Frame(WebSocketFrame.Pong(ping.payload)))).map(_ => false)
        case _ => trueF
      }
    } else s

  private def optionallyDecodeClose(
      s: Stream[IO, QuartzH2WebSocketFrame],
      doDecodeClose: Boolean
  ): Stream[IO, QuartzH2WebSocketFrame] =
    if (!doDecodeClose) {
      s.takeWhile {
        case _: QuartzH2WebSocketFrame.Close => false
        case _                               => true
      }
    } else s

}
