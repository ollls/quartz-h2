package io.quartz.http2

import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._
import java.net.URI
import cats.implicits._
import fs2.{Stream, Pull, Chunk}
import cats.effect.{IO, Fiber, Ref}
import cats.effect.std.Queue
import java.nio.ByteBuffer
import org.typelevel.log4cats.Logger
import io.quartz.MyLogger._
import io.quartz.http2.Constants._
import cats.effect.std.Semaphore
import cats.effect.Deferred

import io.quartz.http2.model.{Headers, Method, StatusCode}
import io.quartz.netio.IOChannel
import io.quartz.http2.model.Method._
import io.quartz.http2.HeaderEncoder
import concurrent.duration.DurationInt

case class ClientResponse(
    status: StatusCode,
    headers: Headers,
    stream: Stream[IO, Byte]
) {
  def transferEncoding(): List[String] = headers.getMval("transfer-encoding")
  def body = stream.compile.toVector.map(_.toArray)
  def bodyAsText = body.map(String(_))
}

object Http2ClientConnection {

  /** Daemon process, sends outbound packets from Queue to the IOChannel.
    * @param ch
    *   outbound IOChannel to write data to
    * @param outq
    *   the Queue to read data from
    * @return
    *   a Fiber that represents the running computation
    */
  private def outBoundWorker(ch: IOChannel, outq: Queue[IO, ByteBuffer]) = (for {
    bb <- outq.take
    _ <- ch.write(bb)
  } yield ()).handleErrorWith(e => Logger[IO].error("Client: outBoundWorker - " + e.toString()))

  /** Reads data from the given IOChannel and processes it with the packet_handler. This function reads data from the
    * IOChannel in chunks representing Http2 packets, converts them to packets using the makePacketStream function, and
    * processes each packet with the packet_handler.
    * @param ch
    *   the IOChannel to read data from
    * @return
    *   a Fiber that represents the running computation
    */
  def make(ch: IOChannel, uri: URI, timeOutMs: Int, incomingWindowSize: Int = 65535) = {
    for {
      outq <- Queue.bounded[IO, ByteBuffer](1)
      f0 <- outBoundWorker(ch, outq).foreverM.start
      refEncoder <- Ref[IO].of[HeaderEncoder](null)
      refDecoder <- Ref[IO].of[HeaderDecoder](null)
      refsId <- Ref[IO].of(1)
      hSem <- Semaphore[IO](1)
      hSem2 <- Semaphore[IO](1)
      awaitSettings <- Deferred[IO, Boolean]
      settings0 <- Ref[IO].of(
        Http2Settings()
      ) // will be loaded with server data when awaitSettings is completed
      inboundWindow <- Ref[IO].of[Long](incomingWindowSize)
      globalBytesOfPendingInboundData <- Ref[IO].of(0L)
      globalTransmitWindow <- Ref[IO].of[Long](
        65535L
      ) // set in upddateInitialWindowSizeAllStreams
    } yield (
      Http2ClientConnection(
        ch,
        timeOutMs,
        uri,
        refsId,
        refEncoder,
        refDecoder,
        outq,
        f0,
        hSem,
        hSem2,
        awaitSettings,
        settings0,
        globalBytesOfPendingInboundData,
        inboundWindow,
        globalTransmitWindow,
        incomingWindowSize
      )
    )

  }
}

/** Represents a HTTP/2 connection.
  *
  * @constructor
  *   Creates a new HTTP/2 connection with the specified parameters.
  * @param ch
  *   The underlying IO channel of the connection.
  * @param sets
  *   The HTTP/2 settings for the connection.
  * @param streamIdRef
  *   connection streamIds, starts from 1,3,5 ...
  * @param headerEncoderRef
  *   The header encoder for the connection.
  * @param headerDecoderRef
  *   The header decoder for the connection.
  * @param outq
  *   The output queue for the connection.
  * @param outBoundFiber
  *   The output fiber for the connection.
  * @param hSem
  *   The semaphore for the connection.
  */
class Http2ClientConnection(
    ch: IOChannel,
    timeOutMs: Int,
    uri: URI,
    streamIdRef: Ref[IO, Int],
    headerEncoderRef: Ref[IO, HeaderEncoder],
    headerDecoderRef: Ref[IO, HeaderDecoder],
    outq: Queue[IO, ByteBuffer],
    outBoundFiber: Fiber[IO, Throwable, Nothing],
    hSem: Semaphore[IO],
    hSem2: Semaphore[IO],
    awaitSettings: Deferred[IO, Boolean],
    settings1: Ref[IO, Http2Settings],
    globalBytesOfPendingInboundData: Ref[IO, Long],
    inboundWindow: Ref[IO, Long],
    transmitWindow: Ref[IO, Long],
    INITIAL_WINDOW_SIZE: Int
) extends Http2ConnectionCommon(
      INITIAL_WINDOW_SIZE,
      globalBytesOfPendingInboundData,
      inboundWindow,
      transmitWindow,
      outq,
      hSem2
    ) {

  class Http2ClientStream(
      val streamId: Int,
      val d: Deferred[IO, (Byte, Headers)],
      val header: ArrayBuffer[ByteBuffer],
      val inDataQ: Queue[cats.effect.IO, ByteBuffer],
      inboundWindow: Ref[IO, Long],
      transmitWindow: Ref[IO, Long],
      bytesOfPendingInboundData: Ref[IO, Long],
      outXFlowSync: Queue[IO, Unit]
  ) extends Http2StreamCommon(bytesOfPendingInboundData, inboundWindow, transmitWindow, outXFlowSync)

  val streamTbl = java.util.concurrent.ConcurrentHashMap[Int, Http2ClientStream](100).asScala

  def getStream(id: Int): Option[Http2StreamCommon] = streamTbl.get(id)

  private[this] def openStream(streamId: Int, in_win: Int) = (for {
    d <- Deferred[IO, (Byte, Headers)]
    inboundWindow <- Ref[IO].of[Long](in_win)
    header <- IO(ArrayBuffer.empty[ByteBuffer])
    inDataQ <- Queue.unbounded[IO, ByteBuffer]
    pendingInBytes <- Ref[IO].of(0L)
    transmitWindow <- Ref[IO].of[Long](
      65535L
    ) // set in upddateInitialWindowSizeAllStreams
    xFlowSync <- Queue.unbounded[IO, Unit]
  } yield (Http2ClientStream(
    streamId,
    d,
    header,
    inDataQ,
    inboundWindow,
    transmitWindow,
    pendingInBytes,
    outXFlowSync = xFlowSync
  )))
    .flatTap(c => IO(this.streamTbl.put(streamId, c)))

  private[this] def decrementGlobalPendingInboundData(decrement: Int) =
    globalBytesOfPendingInboundData.update(_ - decrement)

  private[this] def incrementGlobalPendingInboundData(increment: Int) =
    globalBytesOfPendingInboundData.update(_ + increment)

  private[this] def triggerStreamRst(streamId: Int, flags: Byte) = {
    updateStreamWith(
      5,
      streamId,
      c =>
        for {
          _ <- c.d.complete(null).void
        } yield ()
    )
  }

  private[this] def triggerStream(streamId: Int, flags: Byte): IO[Unit] = {
    updateStreamWith(
      5,
      streamId,
      c =>
        for {
          headerDecoder <- headerDecoderRef.get
          headers <- IO(headerDecoder.decodeHeaders(c.header.toSeq))
          _ <- c.d.complete((flags, headers)).void
        } yield ()
    )
  }

  private[this] def accumHeaders(streamId: Int, bb: ByteBuffer): IO[Unit] =
    updateStreamWith(2, streamId, c => IO(c.header.addOne(bb)))

  private def updateStreamWith(
      num: Int,
      streamId: Int,
      run: Http2ClientStream => IO[Unit]
  ): IO[Unit] = {
    for {
      opt_D <- IO(streamTbl.get(streamId))
      _ <- opt_D match { // Option( null ) gives None
        case None =>
          Logger[IO].error(
            s"Client: updateStreamWith() invalid streamId - $streamId, code=$num"
          ) >> IO.raiseError(
            ErrorGen(streamId, Error.PROTOCOL_ERROR, "invalid stream id")
          )
        case Some(con_rec) => run(con_rec)
      }
    } yield ()

  }

  private[this] def inBoundWorker(ch: IOChannel, timeOutMs: Int) =
    Http2Connection
      .makePacketStream(ch, timeOutMs, Chunk.empty[Byte])
      .foreach(p => packet_handler(p))
      .compile
      .drain
      .handleErrorWith(e => {
        Logger[IO].error("Client: inBoundWorker - " + e.toString()) >> dropStreams()
      })

  private[this] def dropStreams() = for {
    _ <- awaitSettings.complete(true)
    streams <- IO(this.streamTbl.values.toList)
    _ <- streams.traverse(_.d.complete(null))
    _ <- streams.traverse(_.inDataQ.offer(null))
  } yield ()

  /** packet_handler
    * @param packet
    * @return
    */
  private[this] def packet_handler(packet: Chunk[Byte]) = {
    val buffer = packet.toByteBuffer
    val packet0 = buffer.slice // preserve reference to whole packet

    val len = Frames.getLengthField(buffer)
    val frameType = buffer.get()
    val flags = buffer.get()
    val streamId = Frames.getStreamId(buffer)
    for {
      _ <- Logger[IO].trace(
        s"Client: frametype=$frameType with streamId=$streamId len=$len flags=$flags"
      )

      _ <- frameType match {
        case FrameTypes.HEADERS =>
          val padLen: Byte =
            if ((flags & Flags.PADDED) != 0) buffer.get()
            else 0 // advance one byte padding len 1
          val lim = buffer.limit() - padLen
          buffer.limit(lim)
          for {
            _ <- accumHeaders(streamId, buffer)
            _ <- triggerStream(streamId, flags).whenA(
              (flags & Flags.END_HEADERS) != 0
            )
          } yield ()

        // padding for CONTINUATION ??? read about it
        case FrameTypes.CONTINUATION =>
          for {
            _ <- accumHeaders(streamId, buffer)
            _ <- triggerStream(streamId, flags).whenA(
              (flags & Flags.END_HEADERS) != 0
            )
          } yield ()

        case FrameTypes.PING =>
          var data = new Array[Byte](8)
          buffer.get(data)
          if ((flags & Flags.ACK) == 0) {
            for {
              _ <- IO
                .raiseError(
                  ErrorGen(
                    streamId,
                    Error.PROTOCOL_ERROR,
                    "Ping streamId not 0"
                  )
                )
                .whenA(streamId != 0)
              _ <- sendFrame(Frames.mkPingFrame(ack = true, data))
            } yield ()
          } else IO.unit // else if (this.start)

        case FrameTypes.DATA => accumData(streamId, packet0, len)

        case FrameTypes.SETTINGS =>
          // IO.println("ACK CAME").whenA(Flags.ACK(flags) == true) >>
          // sendFrame(Frames.makeSettingsAckFrame()).whenA(Flags.ACK(flags) == true) >>
          // sendFrame(Frames.mkPingFrame( false, Array[Byte]( 0,0,0,0,0,0,0,0))).whenA(Flags.ACK(flags) == true) >>
          awaitSettings.complete(true).whenA(Flags.ACK(flags) == true) >>
            (for {
              previous_remote_settings_iws <- settings1.get.map(settings => settings.INITIAL_WINDOW_SIZE)
              remote_settings <- IO(Http2Settings.fromSettingsArray(buffer, len))
              _ <- settings1.set(remote_settings)

              // transmit window: outbound for us, incoming for server
              _ <- upddateInitialWindowSizeAllStreams(
                previous_remote_settings_iws,
                remote_settings.INITIAL_WINDOW_SIZE
              )
              _ <- Logger[IO].debug(s"Client: Remote INITIAL_WINDOW_SIZE: ${remote_settings.INITIAL_WINDOW_SIZE}")

            } yield ()).whenA(Flags.ACK(flags) == false)

        case FrameTypes.WINDOW_UPDATE => {
          val increment = buffer.getInt() & Masks.INT31
          Logger[IO].debug(
            s"Client: WINDOW_UPDATE $increment $streamId"
          ) >> this
            .updateWindow(streamId, increment)
            .handleErrorWith[Unit] {
              case e @ ErrorRst(streamId, code, name) =>
                Logger[IO].error("Client: Reset frane") >> sendFrame(
                  Frames.mkRstStreamFrame(streamId, code)
                )
              case e @ _ => IO.raiseError(e)
            }
        }
        case FrameTypes.GOAWAY => IO.unit

        case FrameTypes.RST_STREAM =>
          triggerStreamRst(streamId, flags) >>
            Logger[IO].error(
              s"Client: Reset stream (RST_STREAM) - streamId = $streamId"
            )

        case _ => IO.unit
      }
    } yield ()
  }

  /** H2_ClientConnect() initiate incoming connections
    */
  def H2_ClientConnect(): IO[Http2Settings] = for {
    _ <- inBoundWorker(ch, timeOutMs).start // init incoming packet reader
    _ <- ch.write(Constants.getPrefaceBuffer())

    s <- IO(Http2Settings()).flatTap(s => IO { s.INITIAL_WINDOW_SIZE = INITIAL_WINDOW_SIZE })

    _ <- sendFrame(Frames.makeSettingsFrameClient(ack = false, s))

    win_sz <- inboundWindow.get

    _ <- sendFrame(Frames.mkWindowUpdateFrame(0, win_sz.toInt - 65535))
      .whenA(INITIAL_WINDOW_SIZE > 65535)
    _ <- Logger[IO]
      .debug(s"Client: Send initial WINDOW UPDATE global ${win_sz - 65535} streamId=0")
      .whenA(INITIAL_WINDOW_SIZE > 65535)

    _ <- awaitSettings.get
    settings <- settings1.get
    _ <- sendFrame(Frames.makeSettingsAckFrame())

    _ <- headerEncoderRef.set(new HeaderEncoder(settings.HEADER_TABLE_SIZE))
    _ <- headerDecoderRef.set(
      new HeaderDecoder(
        settings.MAX_HEADER_LIST_SIZE,
        settings.HEADER_TABLE_SIZE
      )
    )

  } yield (settings)

  def close() = for {
    _ <- outBoundFiber.cancel
    _ <- ch.close()
  } yield ()

  def doDelete(
      path: String,
      stream: Stream[IO, Byte] = Stream.empty,
      headers: Headers = Headers()
  ) = doMethod(DELETE, path, stream, headers)

  def doPut(
      path: String,
      stream: Stream[IO, Byte] = Stream.empty,
      headers: Headers = Headers()
  ) = doMethod(PUT, path, stream, headers)

  def doPost(
      path: String,
      stream: Stream[IO, Byte] = Stream.empty,
      headers: Headers = Headers()
  ) = doMethod(POST, path, stream, headers)

  def doGet(
      path: String,
      stream: Stream[IO, Byte] = Stream.empty,
      headers: Headers = Headers()
  ) = doMethod(GET, path, stream, headers)

  def doMethod(
      method: Method,
      path: String,
      s0: Stream[IO, Byte] = Stream.empty,
      h0: Headers = Headers()
  ): IO[ClientResponse] = {
    val h1 =
      h0 + (":path" -> path) + (":method" -> method.name) + (":scheme" -> uri
        .getScheme()) + (":authority" -> uri
        .getAuthority())

    val endStreamInHeaders = if (s0 == Stream.empty) true else false
    for {
      _ <- awaitSettings.get
      settings <- settings1.get
      headerEncoder <- headerEncoderRef.get
      // HEADERS /////
      stream <- hSem.acquire.bracket { _ =>
        for {
          streamId <- streamIdRef.getAndUpdate(_ + 2)
          stream <- openStream(streamId, INITIAL_WINDOW_SIZE)
          _ <- headerFrame(
            streamId,
            settings,
            Priority.NoPriority,
            endStreamInHeaders,
            headerEncoder,
            h1
          ).traverse(b => sendFrame(b))

          _ <- sendFrame(Frames.mkWindowUpdateFrame(streamId, INITIAL_WINDOW_SIZE - 65535))
            .whenA(INITIAL_WINDOW_SIZE > 65535 && endStreamInHeaders == false)
          _ <- Logger[IO]
            .debug(s"Client: Send initial WINDOW UPDATE ${INITIAL_WINDOW_SIZE - 65535} streamId=$streamId")
            .whenA(INITIAL_WINDOW_SIZE > 65535 && endStreamInHeaders == false)

        } yield (stream)
      }(_ => hSem.release)

      // DATA /////
      pref <- Ref.of[IO, Chunk[Byte]](Chunk.empty[Byte])
      sts <- settings1.get

      streamId = stream.streamId

      _ <- s0.chunks
        .foreach { chunk =>
          for {
            chunk0 <- pref.get
            _ <- dataFrame(
              sts,
              streamId,
              false,
              chunk0.toByteBuffer
            )
              .traverse(b => sendDataFrame(streamId, b))
              .whenA(chunk0.nonEmpty)
            _ <- pref.set(chunk)
          } yield ()

        }
        .compile
        .drain
        .whenA(endStreamInHeaders == false)

      lastChunk <- pref.get
      _ <- dataFrame(
        sts,
        streamId,
        true,
        lastChunk.toByteBuffer
      )
        .traverse(b => sendDataFrame(streamId, b))
        .whenA(endStreamInHeaders == false)
        .void
      // END OF DATA /////

      _ <- Logger[IO].trace(s"Client: Stream Id: $streamId ${method.name} $path")
      _ <- Logger[IO].trace(s"Client: Stream Id: $streamId request headers: ${h1.printHeaders(" | ")})")

      // wait for response
      pair <- stream.d.get
      _ <- IO
        .raiseError(new java.nio.channels.ClosedChannelException())
        .whenA(pair == null)
      flags = pair._1
      h = pair._2
      _ <- Logger[IO].trace(
        s"Client: Stream Id: $streamId header received from remote"
      )

      status <- IO(h.get(":status") match {
        case None        => StatusCode.InternalServerError
        case Some(value) => StatusCode(value.toInt)
      })

      data_stream <-
        if ((flags & Flags.END_STREAM) == Flags.END_STREAM) IO(Stream.empty)
        else IO(Http2Connection.makeDataStream(this, stream.inDataQ))

      code <- IO(h.get(":status").get)
      _ <- Logger[IO].debug(
        s"Client: Stream Id: $streamId response $code  ${method.name} $path"
      )

      _ <- Logger[IO].trace(s"Client: Stream Id: $streamId response headers: ${h.printHeaders(" | ")})")

    } yield (ClientResponse(status, h, data_stream))
  }

  /** Generate stream header frames from the provided header sequence
    *
    * If the compressed representation of the headers exceeds the MAX_FRAME_SIZE setting of the peer, it will be broken
    * into a HEADERS frame and a series of CONTINUATION frames.
    */

  private[this] def accumData(
      streamId: Int,
      bb: ByteBuffer,
      dataSize: Int
  ): IO[Unit] = {
    for {
      o_c <- IO(this.streamTbl.get(streamId))
      _ <- IO
        .raiseError(
          ErrorGen(streamId, Error.FRAME_SIZE_ERROR, "invalid stream id")
        )
        .whenA(o_c.isEmpty)
      c <- IO(o_c.get)
      _ <- this.incrementGlobalPendingInboundData(dataSize)
      _ <- c.bytesOfPendingInboundData.update(_ + dataSize)
      _ <- c.inDataQ.offer(bb)
    } yield ()

  }

  /*
    When the value of SETTINGS_INITIAL_WINDOW_SIZE changes, a receiver MUST adjust
    the size of all stream flow-control windows that it maintains by the
    difference between the new value and the old value.
   */
  private[this] def updateInitiallWindowSize(
      stream: Http2ClientStream,
      currentWinSize: Int,
      newWinSize: Int
  ) = {
    Logger[IO].info(
      s"Client: Http2Connection.upddateInitialWindowSize( $currentWinSize, $newWinSize)"
    ) >>
      stream.transmitWindow.update(txBytesLeft => newWinSize - (currentWinSize - txBytesLeft)) >> stream.outXFlowSync
        .offer(())
  }

  private[this] def upddateInitialWindowSizeAllStreams(
      currentSize: Int,
      newSize: Int
  ) = {
    Logger[IO].trace(
      s"Client: Http2Connection.upddateInitialWindowSizeAllStreams($currentSize, $newSize)"
    ) >> this.transmitWindow.update(txBytesLeft => newSize - (currentSize - txBytesLeft)) >>
      streamTbl.values.toSeq
        .traverse(stream => updateInitiallWindowSize(stream, currentSize, newSize))
        .void
  }

  private[this] def updateAndCheckGlobalTx(streamId: Int, inc: Int) = {
    for {
      _ <- transmitWindow.update(_ + inc)

      rs <- transmitWindow.get
      // murky issue when cloudfromt servers sends 2147483648 and max int 2147483647
      // h2spect requires dynamic realoc for INITIAL_WINDOW_SIZE ( I will double check on that one)
      // and peps on stackoverflow say that only UPDATE_WINDOW can change that and INITIAL_WINDOW_SIZE is simply ignored !!??
      // cloudfront sends 65536 instead of 65535 !
      // transmit Windows here made as Long, so we have a luxury not to worry much for now
      // commented out temporary?
      /*
      _ <- IO
        .raiseError(
          ErrorGen(
            streamId,
            Error.FLOW_CONTROL_ERROR,
            "Sends multiple WINDOW_UPDATE frames increasing the flow control window to above 2^31-1"
          )
        )
        .whenA(rs > Integer.MAX_VALUE)*/
    } yield ()
  }

  private[this] def updateWindow(streamId: Int, inc: Int): IO[Unit] = {
    // IO.println( "Update Window()") >>
    IO.raiseError(
      ErrorGen(
        streamId,
        Error.PROTOCOL_ERROR,
        "Sends a WINDOW_UPDATE frame with a flow control window increment of 0"
      )
    ).whenA(inc == 0) >> (if (streamId == 0)
                            updateAndCheckGlobalTx(streamId, inc) >>
                              streamTbl.values.toSeq
                                .traverse(stream =>
                                  for {
                                    _ <- stream.transmitWindow.update(_ + inc)
                                    rs <- stream.transmitWindow.get
                                    // murky issue when cloudfromt servers sends 2147483648 and max int 2147483647
                                    // h2spect requires dynamic realoc for INITIAL_WINDOW_SIZE ( I will double check on that one)
                                    // and peps on stackoverflow say that only UPDATE_WINDOW can change that and INITIAL_WINDOW_SIZE is simply ignored !!??
                                    // cloudfront sends 65536 instead of 65535 !
                                    // transmit Windows here made as Long, so we have a luxury not to worry much for now
                                    // commented out temporary?
                                    /*
                                    _ <- IO
                                      .raiseError(
                                        ErrorGen(
                                          streamId,
                                          Error.FLOW_CONTROL_ERROR,
                                          "Sends multiple WINDOW_UPDATE frames increasing the flow control window to above 2^31-1"
                                        )
                                      )
                                      .whenA(rs > Integer.MAX_VALUE)*/
                                    _ <- stream.outXFlowSync.offer(())
                                  } yield ()
                                )
                                .void
                          else
                            updateStreamWith(
                              1,
                              streamId,
                              stream =>
                                for {
                                  _ <- stream.transmitWindow.update(_ + inc)
                                  rs <- stream.transmitWindow.get
                                  _ <- IO
                                    .raiseError(
                                      ErrorRst(
                                        streamId,
                                        Error.FLOW_CONTROL_ERROR,
                                        ""
                                      )
                                    )
                                    .whenA(rs >= Integer.MAX_VALUE)
                                  _ <- stream.outXFlowSync.offer(())
                                } yield ()
                            ))
  }

}
