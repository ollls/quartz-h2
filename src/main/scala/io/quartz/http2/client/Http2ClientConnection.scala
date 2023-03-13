package io.quartz.http2

import io.quartz.netio.IOChannel
import cats.effect.IO
import fs2.Chunk
import fs2.{Stream, Pull}
import cats.effect.{Fiber, Ref}
import cats.effect.std.Queue
import java.nio.ByteBuffer
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import io.quartz.MyLogger._
import io.quartz.http2.Constants._
import cats.effect.std.Semaphore
import cats.effect.Deferred
import io.quartz.http2.model.Headers
import io.quartz.http2.model.Method._
import io.quartz.http2.model.Method
import io.quartz.http2.HeaderEncoder
import scala.collection.mutable.ArrayBuffer
import cats.implicits._
import cats.syntax.all._
import io.quartz.http2.model.StatusCode
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
  private[this] def outBoundWorker(ch: IOChannel, outq: Queue[IO, ByteBuffer]) = (for {
    bb <- outq.take
    _ <- ch.write(bb)
  } yield ()).handleErrorWith(e => Logger[IO].error(e.toString()))

  /** Reads data from the given IOChannel and processes it with the packet_handler. This function reads data from the
    * IOChannel in chunks representing Http2 packets, converts them to packets using the makePacketStream function, and
    * processes each packet with the packet_handler.
    * @param ch
    *   the IOChannel to read data from
    * @return
    *   a Fiber that represents the running computation
    */
  def make(ch: IOChannel, incomingWindowSize: Int = 65535) = {
    for {
      outq <- Queue.bounded[IO, ByteBuffer](1)

      f0 <- outBoundWorker(ch, outq).foreverM.start
      refEncoder <- Ref[IO].of[HeaderEncoder](null)
      refDecoder <- Ref[IO].of[HeaderDecoder](null)
      refsId <- Ref[IO].of(1)
      hSem <- Semaphore[IO](1)
      awaitSettings <- Deferred[IO, Boolean]
      settings0 <- Ref[IO].of(
        Http2Settings()
      ) // will be loaded with server data when awaitSettings is completed
      inboundWindow <- Ref[IO].of(65535L)
      globalBytesOfPendingInboundData <- Ref[IO].of(0)
      globalTransmitWindow <- Ref[IO].of[Long](
        65535L
      ) // set in upddateInitialWindowSizeAllStreams
    } yield (
      Http2ClientConnection(
        ch,
        refsId,
        refEncoder,
        refDecoder,
        outq,
        f0,
        hSem,
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
    streamIdRef: Ref[IO, Int],
    headerEncoderRef: Ref[IO, HeaderEncoder],
    headerDecoderRef: Ref[IO, HeaderDecoder],
    outq: Queue[IO, ByteBuffer],
    outBoundFiber: Fiber[IO, Throwable, Nothing],
    hSem: Semaphore[IO],
    awaitSettings: Deferred[IO, Boolean],
    settings1: Ref[IO, Http2Settings],
    val globalBytesOfPendingInboundData: Ref[IO, Int],
    inboundWindow: Ref[IO, Long],
    transmitWindow: Ref[IO, Long],
    INITIAL_WINDOW_SIZE: Int
) {
  import scala.jdk.CollectionConverters.*
  class Http2StreamClient(
      val streamId: Int,
      val d: Deferred[IO, (Byte, Headers)],
      val header: ArrayBuffer[ByteBuffer],
      val inDataQ: Queue[cats.effect.IO, ByteBuffer],
      val inboundWindow: Ref[IO, Long],
      val transmitWindow: Ref[IO, Long],
      val bytesOfPendingInboundData: Ref[IO, Int],
      val outXFlowSync: Queue[IO, Unit]
  )
  val streamTbl =
    java.util.concurrent.ConcurrentHashMap[Int, Http2StreamClient](100).asScala

  private[this] def openStream(streamId: Int, in_win: Int) = (for {
    d <- Deferred[IO, (Byte, Headers)]
    inboundWindow <- Ref[IO].of[Long](in_win)
    header <- IO(ArrayBuffer.empty[ByteBuffer])
    inDataQ <- Queue.unbounded[IO, ByteBuffer]
    pendingInBytes <- Ref[IO].of(0)
    transmitWindow <- Ref[IO].of[Long](
      65535L
    ) // set in upddateInitialWindowSizeAllStreams
    xFlowSync <- Queue.unbounded[IO, Unit]
  } yield (Http2StreamClient(
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

  private def triggerStream(streamId: Int, flags: Byte): IO[Unit] = {
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

  private[this] def updateStreamWith(
      num: Int,
      streamId: Int,
      run: Http2StreamClient => IO[Unit]
  ): IO[Unit] = {
    for {
      opt_D <- IO(streamTbl.get(streamId))
      _ <- opt_D match { // Option( null ) gives None
        case None =>
          Logger[IO].error(
            s"updateStreamWith() invalid streamId - $streamId, code=$num"
          ) >> IO.raiseError(
            ErrorGen(streamId, Error.PROTOCOL_ERROR, "invalid stream id")
          )
        case Some(con_rec) => run(con_rec)
      }
    } yield ()

  }

  /** Creates a stream of HTTP/2 packets from the specified IO channel.
    *
    * @param ch
    *   The IO channel to read packets from.
    * @param keepAliveMs
    *   The keep-alive time in milliseconds.
    * @param leftOver
    *   The leftover bytes left from TLS negotiation, if any
    * @return
    *   A fs2 stream of HTTP/2 packets.
    */
  private[this] def makePacketStream(
      ch: IOChannel,
      keepAliveMs: Int,
      leftOver: Chunk[Byte]
  ): Stream[IO, Chunk[Byte]] = {
    val s0 = Stream.chunk[IO, Byte](leftOver)
    val s1 =
      Stream
        .repeatEval(ch.read(keepAliveMs))
        .flatMap(c0 => Stream.chunk(c0))

    def go2(s: Stream[IO, Byte], chunk: Chunk[Byte]): Pull[IO, Byte, Unit] = {

      val bb = chunk.toByteBuffer
      val len = Frames.getLengthField(bb) + 3 + 1 + 1 + 4

      if (chunk.size > len) {
        Pull.output[IO, Byte](chunk.take(len)) >> go2(s, chunk.drop(len))
      } else if (chunk.size == len) Pull.output[IO, Byte](chunk)
      else { go(s, chunk) }
    }

    def go(s: Stream[IO, Byte], leftover: Chunk[Byte]): Pull[IO, Byte, Unit] = {
      s.pull.uncons.flatMap {
        case Some((hd1, tl)) =>
          val hd = leftover ++ hd1
          val bb = hd.toByteBuffer
          val len = Frames.getLengthField(bb) + 3 + 1 + 1 + 4
          (if (hd.size == len) {
             Pull.output[IO, Byte](hd) >> go(tl, Chunk.empty[Byte])
           } else if (hd.size > len) {
             Pull.output[IO, Byte](hd.take(len)) >> go2(tl, hd.drop(len)) >> go(
               tl,
               Chunk.empty[Byte]
             )
           } else {
             go(tl, hd)
           })

        case None => Pull.done
      }
    }
    go(s0 ++ s1, Chunk.empty[Byte]).stream.chunks
  }

  def settings = settings1.get

  private[this] def inBoundWorker(ch: IOChannel) =
    makePacketStream(ch, 100000, Chunk.empty[Byte])
      .foreach(p => packet_handler(p))
      .compile
      .drain
      .handleErrorWith(e => dropStreams())

  private[this] def dropStreams() = for {
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
        s"frametype=$frameType with streamId=$streamId len=$len flags=$flags"
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

        case FrameTypes.DATA => accumData(streamId, packet0, len)

        case FrameTypes.SETTINGS =>
          for {
            current_size <- settings1.get.map(settings => settings.INITIAL_WINDOW_SIZE)
            res <- IO(Http2Settings.fromSettingsArray(buffer, len))
            _ <- settings1.set(res)
            _ <- awaitSettings.complete(true)
            _ <- upddateInitialWindowSizeAllStreams(
              current_size,
              res.INITIAL_WINDOW_SIZE
            )
          } yield ()
        /*
            .flatTap(settings =>
              initFromSettings(settings).whenA(Flags.ACK(flags) == false)
            )
            .map(settings => sets.set(settings))
            .whenA(Flags.ACK(flags) == false)
            .onError { case e: scala.MatchError =>
              sendFrame(
                Frames.mkPingFrame(ack = true, Array.fill[Byte](8)(0x0))
              )
            }
            .whenA(true)*/
        case FrameTypes.WINDOW_UPDATE => {
          val increment = buffer.getInt() & Masks.INT31
          Logger[IO].debug(s"WINDOW_UPDATE $increment $streamId") >> this
            .updateWindow(streamId, increment)
            .handleErrorWith[Unit] {
              case e @ ErrorRst(streamId, code, name) =>
                Logger[IO].error("Reset frane") >> sendFrame(
                  Frames.mkRstStreamFrame(streamId, code)
                )
              case e @ _ => IO.raiseError(e)
            }
        }
        case FrameTypes.GOAWAY => IO.unit
        case _                 => IO.unit
      }
    } yield ()
  }

  /** Takes a slice of the specified length from the specified ByteBuffer.
    *
    * @param buf
    *   The ByteBuffer to take the slice from.
    * @param len
    *   The length of the slice to take.
    * @return
    *   A new ByteBuffer containing the specified slice of the input ByteBuffer.
    */
  private[this] def takeSlice(buf: ByteBuffer, len: Int): ByteBuffer = {
    val head = buf.slice.limit(len)
    buf.position(len)
    head
  }

  /** H2_ClientConnect() initiate incoming connections
    */
  def H2_ClientConnect(): IO[Http2Settings] = for {
    _ <- inBoundWorker(ch).start // init incoming packet reader
    _ <- ch.write(Constants.getPrefaceBuffer())

    s <- IO(Http2Settings()).flatTap(s => IO { s.INITIAL_WINDOW_SIZE = INITIAL_WINDOW_SIZE })
    _ <- sendFrame(
      Frames.makeSettingsFrameClient(ack = false, s)
    )

    win_sz <- inboundWindow.get

    // new value of INITIAL_WINDOW_SIZE in FrameTypes.SETTINGS packet not always getting propogated in servers
    // check RFC ???, but all browser and clients always send update WINSIZE
    _ <- sendFrame(Frames.mkWindowUpdateFrame(0, INITIAL_WINDOW_SIZE - 65535))
      .whenA(INITIAL_WINDOW_SIZE > 65535)

    _ <- awaitSettings.get
    settings <- settings1.get

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
      h0 + (":path" -> path) + (":method" -> method.name) + (":scheme" -> "http") // TDOD https vs http

    val endStreamInHeaders = if (s0 == Stream.empty) true else false

    // val requestIO = for {
    //  emptyTH <- Deferred[IO, Headers]
    //  _ <- emptyTH.complete(Headers()) // complete with empty
    // } yield (Request(h1, s0, emptyTH))

    for {
      _ <- awaitSettings.get
      settings <- settings1.get
      headerEncoder <- headerEncoderRef.get
      // HEADERS /////
      // req <- requestIO
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
        } yield (stream)
      }(_ => hSem.release)

      // DATA /////
      pref <- Ref.of[IO, Chunk[Byte]](Chunk.empty[Byte])

      streamId = stream.streamId

      _ <- s0.chunks
        .foreach { chunk =>
          for {
            chunk0 <- pref.get
            _ <- dataFrame(
              streamId,
              false,
              chunk0.toByteBuffer,
              settings.MAX_FRAME_SIZE - 128
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
        streamId,
        true,
        lastChunk.toByteBuffer,
        settings.MAX_FRAME_SIZE - 128
      )
        .traverse(b => sendDataFrame(streamId, b))
        .whenA(endStreamInHeaders == false)
        .void
      // END OF DATA /////

      _ <- Logger[IO].trace(s"Stream Id: $streamId ${GET.name} $path")
      // wait for response
      pair <- stream.d.get
      _ <- IO
        .raiseError(new java.nio.channels.ClosedChannelException())
        .whenA(pair == null)
      flags = pair._1
      h = pair._2
      _ <- Logger[IO].trace(s"Stream Id: $streamId header received from remote")

      status <- IO(h.get(":status") match {
        case None        => StatusCode.InternalServerError
        case Some(value) => StatusCode(value.toInt)
      })

      data_stream <-
        if ((flags & Flags.END_STREAM) == Flags.END_STREAM) IO(Stream.empty)
        else IO(makeDataStream(this, stream.inDataQ))

      code <- IO(h.get(":status").get)
      _ <- Logger[IO].debug(
        s"Stream Id: $streamId response $code  ${GET.name} $path"
      )

    } yield (ClientResponse(status, h, data_stream))
  }

  /** Sends an HTTP/2 frame by offering it to the outbound queue.
    *
    * @param b
    *   The HTTP/2 packet to send.
    */
  private[this] def sendFrame(b: ByteBuffer) = outq.offer(b)

  /** Generate stream header frames from the provided header sequence
    *
    * If the compressed representation of the headers exceeds the MAX_FRAME_SIZE setting of the peer, it will be broken
    * into a HEADERS frame and a series of CONTINUATION frames.
    */
  //////////////////////////////
  private[this] def headerFrame( // TODOD pbly not working for multi-packs
      streamId: Int,
      settings: Http2Settings,
      priority: Priority,
      endStream: Boolean,
      headerEncoder: HeaderEncoder,
      headers: Headers
  ): Seq[ByteBuffer] = {
    val rawHeaders = headerEncoder.encodeHeaders(headers)

    val limit = settings.MAX_FRAME_SIZE - 61

    val headersPrioritySize =
      if (priority.isDefined) 5 else 0 // priority(4) + weight(1), padding = 0

    if (rawHeaders.remaining() + headersPrioritySize <= limit) {
      val acc = new ArrayBuffer[ByteBuffer]
      acc.addOne(
        Frames.mkHeaderFrame(
          streamId,
          priority,
          endHeaders = true,
          endStream,
          padding = 0,
          rawHeaders
        )
      )

      acc.toSeq
    } else {
      // need to fragment
      val acc = new ArrayBuffer[ByteBuffer]

      val headersBuf = takeSlice(rawHeaders, limit - headersPrioritySize)
      acc += Frames.mkHeaderFrame(
        streamId,
        priority,
        endHeaders = false,
        endStream,
        padding = 0,
        headersBuf
      )

      while (rawHeaders.hasRemaining) {
        val size = math.min(limit, rawHeaders.remaining)
        val continueBuf = takeSlice(rawHeaders, size)
        val endHeaders = !rawHeaders.hasRemaining
        acc += Frames.mkContinuationFrame(streamId, endHeaders, continueBuf)
      }
      acc.toSeq
    }
  }

  private[this] def parseFrame(bb: ByteBuffer) = {
    val sbb = bb.slice();

    val len = Frames.getLengthField(sbb)
    val frameType = sbb.get()
    val flags = sbb.get()
    val streamId = Frames.getStreamId(sbb)

    (len, frameType, flags, streamId)
  }

  private[this] def dataEvalEffectProducer(
      c: Http2ClientConnection,
      q: Queue[IO, ByteBuffer]
  ): IO[ByteBuffer] = {
    for {
      bb <- q.take
      _ <- IO
        .raiseError(java.nio.channels.ClosedChannelException())
        .whenA(bb == null)
      tp <- IO(parseFrame(bb))
      streamId = tp._4
      len = tp._1

      o_stream <- IO(c.streamTbl.get(streamId))
      _ <- IO
        .raiseError(
          ErrorGen(streamId, Error.FRAME_SIZE_ERROR, "invalid stream id")
        )
        .whenA(o_stream.isEmpty)
      stream <- IO(o_stream.get)

      // global counter
      _ <- c.decrementGlobalPendingInboundData(len)
      // local counter
      _ <- c.updateStreamWith(
        0,
        streamId,
        c => c.bytesOfPendingInboundData.update(_ - len)
      )

      localWin <- stream.inboundWindow.get

      pending_sz <- c.globalBytesOfPendingInboundData.get
      updWin =
        if (INITIAL_WINDOW_SIZE > pending_sz) INITIAL_WINDOW_SIZE - pending_sz
        else INITIAL_WINDOW_SIZE
      _ <-
        if (updWin > INITIAL_WINDOW_SIZE * 0.7 && localWin < INITIAL_WINDOW_SIZE * 0.3) {
          Logger[IO].trace(
            s"Send WINDOW UPDATE local on processing incoming data=$updWin localWin=$localWin"
          ) >> c
            .sendFrame(
              Frames.mkWindowUpdateFrame(
                streamId,
                if (updWin > 0) updWin else INITIAL_WINDOW_SIZE
              )
            ) >>
            stream.inboundWindow.update(_ + updWin)
        } else {
          Logger[IO].trace(
            ">>>>>>>>>> still processing incoming data, pause remote, pending data = " + pending_sz
          ) >> IO.unit
        }

    } yield (bb)
  }

  private[this] def makeDataStream(
      c: Http2ClientConnection,
      q: Queue[IO, ByteBuffer]
  ): Stream[IO, Byte] = {
    val dataStream0 =
      Stream.eval(dataEvalEffectProducer(c, q)).repeat.takeThrough { buffer =>
        val len = Frames.getLengthField(buffer)
        val frameType = buffer.get()
        val flags = buffer.get()
        val _ = Frames.getStreamId(buffer)

        val padLen: Byte =
          if ((flags & Flags.PADDED) != 0) buffer.get()
          else 0 // advance one byte padding len 1

        val lim = buffer.limit() - padLen
        buffer.limit(lim)
        val continue: Boolean = ((flags & Flags.END_STREAM) == 0)
        continue // true if flags has no end stream
      }

    dataStream0.flatMap(b => Stream.emits(ByteBuffer.allocate(b.remaining).put(b).array()))
  }

  private[this] def processInboundGlobalFlowControl(streamId: Int, dataSize: Int) = {
    for {
      win_sz <- this.inboundWindow.get

      pending_sz <- this.globalBytesOfPendingInboundData.get

      WINDOW <- IO(INITIAL_WINDOW_SIZE)

      updWin <- if (WINDOW > pending_sz) IO(WINDOW - pending_sz) else IO(WINDOW)
      _ <-
        if (updWin > WINDOW * 0.7 && win_sz < WINDOW * 0.3) {
          this.inboundWindow.update(_ + updWin) >> Logger[IO].debug(
            "Send WINDOW UPDATE global = " + updWin
          ) >> sendFrame(Frames.mkWindowUpdateFrame(0, if (updWin > 0) updWin else WINDOW))
        } else IO.unit
    } yield ()
  }

  private[this] def accumData(
      streamId: Int,
      bb: ByteBuffer,
      dataSize: Int
  ): IO[Unit] = {
    for {
      test <- globalBytesOfPendingInboundData.get
      o_c <- IO(this.streamTbl.get(streamId))
      _ <- IO
        .raiseError(
          ErrorGen(streamId, Error.FRAME_SIZE_ERROR, "invalid stream id")
        )
        .whenA(o_c.isEmpty)
      c <- IO(o_c.get)
      localWin_sz <- c.inboundWindow.get
      _ <- processInboundGlobalFlowControl(streamId, dataSize) >>
        this.inboundWindow.update(_ - dataSize) >>
        this.incrementGlobalPendingInboundData(dataSize) >>
        c.inboundWindow.update(_ - dataSize) >>
        c.bytesOfPendingInboundData.update(_ + dataSize)

      _ <- c.inDataQ.offer(bb)

    } yield ()

  }

  private[this] case class txWindow_SplitDataFrame(buffer: ByteBuffer, dataLen: Int)

  private[this] def splitDataFrames(
      bb: ByteBuffer,
      requiredLen: Long
  ): (txWindow_SplitDataFrame, Option[txWindow_SplitDataFrame]) = {
    val original_bb = bb.slice()
    val len = Frames.getLengthField(bb)
    val frameType = bb.get()
    val flags = bb.get()
    val streamId = Frames.getStreamId(bb)

    if (requiredLen < len) {
      val buf0 = Array.ofDim[Byte](requiredLen.toInt)
      bb.get(buf0)

      val dataFrame1 =
        Frames.mkDataFrame(streamId, false, padding = 0, ByteBuffer.wrap(buf0))
      val dataFrame2 = Frames.mkDataFrame(streamId, false, padding = 0, bb)

      (
        txWindow_SplitDataFrame(dataFrame1, requiredLen.toInt),
        Some(txWindow_SplitDataFrame(dataFrame2, len - requiredLen.toInt))
      )

    } else (txWindow_SplitDataFrame(original_bb, len), None)
  }

  /*
    When the value of SETTINGS_INITIAL_WINDOW_SIZE changes, a receiver MUST adjust
    the size of all stream flow-control windows that it maintains by the
    difference between the new value and the old value.
   */
  private[this] def updateInitiallWindowSize(
      stream: Http2StreamClient,
      currentWinSize: Int,
      newWinSize: Int
  ) = {
    Logger[IO].info(
      s"Http2Connection.upddateInitialWindowSize( $currentWinSize, $newWinSize)"
    ) >>
      stream.transmitWindow.update(txBytesLeft => newWinSize - (currentWinSize - txBytesLeft)) >> stream.outXFlowSync
        .offer(())
  }

  private[this] def upddateInitialWindowSizeAllStreams(
      currentSize: Int,
      newSize: Int
  ) = {
    Logger[IO].trace(
      s"Http2Connection.upddateInitialWindowSizeAllStreams($currentSize, $newSize)"
    ) >> this.transmitWindow.update(txBytesLeft => newSize - (currentSize - txBytesLeft)) >>
      streamTbl.values.toSeq
        .traverse(stream => updateInitiallWindowSize(stream, currentSize, newSize))
        .void
  }

  private[this] def txWindow_Transmit(
      stream: Http2StreamClient,
      bb: ByteBuffer,
      data_len: Int
  ): IO[Long] = {
    for {
      tx_g <- transmitWindow.get
      tx_l <- stream.transmitWindow.get
      bytesCredit <- IO(Math.min(tx_g, tx_l))

      _ <-
        if (bytesCredit > 0)
          (for {
            rlen <- IO(Math.min(bytesCredit, data_len))
            frames <- IO(splitDataFrames(bb, rlen))

            _ <- sendFrame(frames._1.buffer)

            _ <- transmitWindow.update(_ - rlen)
            _ <- stream.transmitWindow.update(_ - rlen)

            _ <- frames._2 match {
              case Some(f0) =>
                stream.outXFlowSync.take >> txWindow_Transmit(
                  stream,
                  f0.buffer,
                  f0.dataLen
                )
              case None => IO.unit
            }

          } yield ())
        else stream.outXFlowSync.take >> txWindow_Transmit(stream, bb, data_len)

    } yield (bytesCredit)
  }

  /** Generate stream data frame(s) for the specified data
    *
    * If the data exceeds the peers MAX_FRAME_SIZE setting, it is fragmented into a series of frames.
    */
  private[this] def dataFrame(
      streamId: Int,
      endStream: Boolean,
      data: ByteBuffer,
      frameSize: Int
  ): scala.collection.immutable.Seq[ByteBuffer] = {
    val limit = frameSize
    // settings.MAX_FRAME_SIZE - 128

    if (data.remaining <= limit) {

      val acc = new ArrayBuffer[ByteBuffer]
      acc.addOne(Frames.mkDataFrame(streamId, endStream, padding = 0, data))

      acc.toSeq
    } else { // need to fragment
      val acc = new ArrayBuffer[ByteBuffer]
      while (data.hasRemaining) {
        val len = math.min(data.remaining(), limit)
        val cur_pos = data.position()
        val thisData = data.slice.limit(len)
        data.position(cur_pos + len)
        val eos = endStream && !data.hasRemaining
        acc.addOne(Frames.mkDataFrame(streamId, eos, padding = 0, thisData))
      }
      acc.toSeq
    }
  }

  private[this] def sendDataFrame(streamId: Int, bb: ByteBuffer): IO[Unit] =
    for {
      t <- IO(parseFrame(bb))
      len = t._1
      _ <- Logger[IO].trace(s"sendDataFrame() - $len bytes")
      opt_D <- IO(streamTbl.get(streamId))
      _ <- opt_D match {
        case Some(ce) =>
          for {
            _ <- txWindow_Transmit(ce, bb, len)
          } yield ()
        case None => Logger[IO].error("sendDataFrame lost streamId")
      }
    } yield ()

  private[this] def updateAndCheckGlobalTx(streamId: Int, inc: Int) = {
    for {
      _ <- transmitWindow.update(_ + inc)
      rs <- transmitWindow.get
      _ <- IO
        .raiseError(
          ErrorGen(
            streamId,
            Error.FLOW_CONTROL_ERROR,
            "Sends multiple WINDOW_UPDATE frames increasing the flow control window to above 2^31-1"
          )
        )
        .whenA(rs >= Integer.MAX_VALUE)
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
                                    _ <- IO
                                      .raiseError(
                                        ErrorGen(
                                          streamId,
                                          Error.FLOW_CONTROL_ERROR,
                                          "Sends multiple WINDOW_UPDATE frames increasing the flow control window to above 2^31-1"
                                        )
                                      )
                                      .whenA(rs >= Integer.MAX_VALUE)
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
