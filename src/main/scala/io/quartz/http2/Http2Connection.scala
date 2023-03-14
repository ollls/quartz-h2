package io.quartz.http2

import scala.collection.mutable.ArrayBuffer
import java.nio.channels.AsynchronousSocketChannel
import cats.effect.{FiberIO, IO, Ref, Deferred}
import cats.effect.std.Semaphore
import fs2.{Chunk, Pull, Stream}

import java.nio.ByteBuffer
import cats.Order
import cats.effect.std.PQueue
import io.quartz.http2.Constants._

import io.quartz.http2.model.{Request, Response, Headers, ContentType, StatusCode}
import io.quartz.netio._

import cats.effect.MonadCancel
import cats.implicits._

import scala.collection.mutable
import cats.effect.std.Queue
import cats.effect.std.Dequeue
import cats.Monoid

import scala.concurrent.duration.FiniteDuration
import concurrent.duration.DurationInt

import java.nio.charset.StandardCharsets

import scala.jdk.CollectionConverters.MapHasAsScala
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import io.quartz.MyLogger._

import io.quartz.http2.routes.Routes
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import cats.effect.kernel.MonadCancel.apply

object Http2Connection {

  val FAST_MODE = true

  // TBD - true for now
  implicit val orderForPriorityPackets: Order[ByteBuffer] =
    Order.fromLessThan((_, _) => true)

  private[this] def outBoundWorkerProc(
      ch: IOChannel,
      outq: Queue[IO, ByteBuffer],
      shutdown: Deferred[IO, Boolean]
  ): IO[Boolean] = {
    for {
      bb <- outq.take
      // _ <- Logger[IO].trace( "packet is about to send")
      res <- if (bb == null) IO(true) else IO(false)
      _ <- ch.write(bb).whenA(res == false)
      _ <- Logger[IO].debug("Shutdown outbound H2 packet sender").whenA(res == true)
      _ <- shutdown.complete(true).whenA(res == true)
    } yield (res)
  }

  def make(
      ch: IOChannel,
      id : Long,
      maxStreams: Int,
      keepAliveMs: Int,
      httpRoute: Request => IO[Option[Response]],
      in_winSize: Int,
      http11request: Option[Request]
  ): IO[Http2Connection] = {
    for {
      _ <- Logger[IO].debug("Http2Connection.make()")
      shutdownPromise <- Deferred[IO, Boolean]

      outq <- Dequeue.bounded[IO, ByteBuffer](1)
      outDataQEventQ <- Queue.unbounded[IO, Boolean]
      http11Req_ref <- Ref[IO].of[Option[Request]](http11request)

      hSem <- Semaphore[IO](1)

      globalTransmitWindow <- Ref[IO].of[Long](65535) // (default_server_settings.INITIAL_WINDOW_SIZE)
      globalInboundWindow <- Ref[IO].of(65535L) // (default_server_settings.INITIAL_WINDOW_SIZE)

      globalBytesOfPendingInboundData <- Ref[IO].of(0)
      runMe2 = outBoundWorkerProc(ch, outq, shutdownPromise)
        .handleErrorWith(e => Logger[IO].debug("outBoundWorkerProc fiber: " + e.toString()))
        .iterateUntil(_ == true)
        .start

      _ <- runMe2

      c <- IO(
        new Http2Connection(
          ch,
          id,
          httpRoute,
          http11Req_ref,
          outq,
          outDataQEventQ,
          globalTransmitWindow,
          globalBytesOfPendingInboundData,
          globalInboundWindow,
          shutdownPromise,
          hSem,
          maxStreams,
          keepAliveMs,
          in_winSize
        )
      )
      runMe = c.streamDataOutWorker
        .handleErrorWith(e => Logger[IO].error("streamDataOutWorker fiber: " + e.toString()))
        .iterateUntil(_ == true)
        .start

      _ <- runMe

      _ <- Logger[IO].trace("Http2Connection.make() - Start data worker")

    } yield c
  }

  private[this] def parseFrame(bb: ByteBuffer) = {
    val sbb = bb.slice();

    val len = Frames.getLengthField(sbb)
    val frameType = sbb.get()
    val flags = sbb.get()
    val streamId = Frames.getStreamId(sbb)

    (len, frameType, flags, streamId)
  }

  private[this] def dataEvalEffectProducer(c: Http2Connection, q: Queue[IO, ByteBuffer]): IO[ByteBuffer] = {
    for {
      bb <- q.take
      tp <- IO(parseFrame(bb))
      streamId = tp._4
      len = tp._1

      o_stream <- IO(c.streamTbl.get(streamId))
      _ <- IO.raiseError(ErrorGen(streamId, Error.FRAME_SIZE_ERROR, "invalid stream id")).whenA(o_stream.isEmpty)
      stream <- IO(o_stream.get)

      // global counter
      _ <- c.decrementGlobalPendingInboundData(len)
      // local counter
      _ <- c.updateStreamWith(0, streamId, c => c.bytesOfPendingInboundData.update(_ - len))

      localWin <- stream.inboundWindow.get

      WINDOW <- IO(c.settings.INITIAL_WINDOW_SIZE)
      pending_sz <- c.globalBytesOfPendingInboundData.get
      updWin = if (WINDOW > pending_sz) WINDOW - pending_sz else WINDOW
      _ <-
        if (updWin > WINDOW * 0.7 && localWin < WINDOW * 0.3) {
          Logger[IO].trace(s"Send WINDOW UPDATE local on processing incoming data=$updWin localWin=$localWin") >> c
            .sendFrame(Frames.mkWindowUpdateFrame(streamId, if (updWin > 0) updWin else WINDOW)) >>
            stream.inboundWindow.update(_ + updWin)
        } else {
          Logger[IO].trace(
            ">>>>>>>>>>>>>>>>>>>>>>>>>> still processing incoming data, pause remote, pending data = " + pending_sz
          ) >> IO.unit
        }

    } yield (bb)
  }

  private[this] def makeDataStream(c: Http2Connection, q: Queue[IO, ByteBuffer]) = {
    val dataStream0 = Stream.eval(dataEvalEffectProducer(c, q)).repeat.takeThrough { buffer =>
      val len = Frames.getLengthField(buffer)
      val frameType = buffer.get()
      val flags = buffer.get()
      val _ = Frames.getStreamId(buffer)

      val padLen: Byte = if ((flags & Flags.PADDED) != 0) buffer.get() else 0 // advance one byte padding len 1

      val lim = buffer.limit() - padLen
      buffer.limit(lim)
      val continue: Boolean = ((flags & Flags.END_STREAM) == 0)
      continue // true if flags has no end stream
    }

    dataStream0.flatMap(b => Stream.emits(ByteBuffer.allocate(b.remaining).put(b).array()))

  }

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
          (if (hd.size == len) { Pull.output[IO, Byte](hd) >> go(tl, Chunk.empty[Byte]) }
           else if (hd.size > len) {
             Pull.output[IO, Byte](hd.take(len)) >> go2(tl, hd.drop(len)) >> go(tl, Chunk.empty[Byte])
           } else {
             go(tl, hd)
           })

        case None => Pull.done
      }
    }
    go(s0 ++ s1, Chunk.empty[Byte]).stream.chunks
  }
}
case class Http2Stream(
    active: Ref[IO, Boolean],
    d: Deferred[IO, Headers],
    header: ArrayBuffer[ByteBuffer],
    trailing_header: ArrayBuffer[ByteBuffer],
    inDataQ: Queue[IO, ByteBuffer], // accumulate data packets in stream function
    outDataQ: Queue[IO, ByteBuffer], // priority outbound queue for all data frames, scanned by one thread
    outXFlowSync: Queue[IO, Unit], // flow control sync queue for data frames
    transmitWindow: Ref[IO, Long],
    syncUpdateWindowQ: Queue[IO, Unit],
    bytesOfPendingInboundData: Ref[IO, Int], // metric
    inboundWindow: Ref[IO, Long],
    contentLenFromHeader: Deferred[IO, Option[Int]],
    trailingHeader: Deferred[IO, Headers],
    done: Deferred[IO, Unit]
) {
  var endFlag = false // half-closed if true
  var endHeadersFlag = false
  var contentLenFromDataFrames = 0

}

class Http2Connection(
    ch: IOChannel,
    val id : Long,
    httpRoute: Request => IO[Option[Response]],
    httpReq11: Ref[IO, Option[Request]],
    outq: Queue[IO, ByteBuffer],
    outDataQEventQ: Queue[IO, Boolean],
    globalTransmitWindow: Ref[IO, Long],
    val globalBytesOfPendingInboundData: Ref[IO, Int], // metric
    globalInboundWindow: Ref[IO, Long],
    shutdownD: Deferred[IO, Boolean],
    hSem: Semaphore[IO],
    MAX_CONCURRENT_STREAMS: Int,
    HTTP2_KEEP_ALIVE_MS: Int,
    INITIAL_WINDOW_SIZE: Int
) {

  val settings: Http2Settings = new Http2Settings()
  val settings_client = new Http2Settings()
  var settings_done = false

  var concurrentStreams = new AtomicInteger(0)

  var start = true
  // streamID of the header which is currently fetching from remote, any other header will trigger GoAway
  var headerStreamId = 0
  var lastStreamId = 0

  val headerEncoder = new HeaderEncoder(settings.HEADER_TABLE_SIZE)
  val headerDecoder = new HeaderDecoder(settings.MAX_HEADER_LIST_SIZE, settings.HEADER_TABLE_SIZE)

  val streamTbl = java.util.concurrent.ConcurrentHashMap[Int, Http2Stream](100).asScala

  // var statRefresh = 0

  private[this] def decrementGlobalPendingInboundData(decrement: Int) =
    globalBytesOfPendingInboundData.update(_ - decrement)
  private[this] def incrementGlobalPendingInboundData(increment: Int) =
    globalBytesOfPendingInboundData.update(_ + increment)

  def shutdown: IO[Unit] =
    Logger[IO].info("Http2Connection.shutdown") >> outDataQEventQ.offer(true) >> outq.offer(null) >> shutdownD.get.void

  //////////////////////////////////////////
  private[this] def streamDataOutBoundProcessor(streamId: Int, c: Http2Stream): IO[Unit] = {
    for {
      opt <- c.outDataQ.tryTake

      res <- opt match {
        case Some(data_frame) =>
          for {
            t <- IO(Http2Connection.parseFrame(data_frame))
            // len = t._1
            // frameType = t._2
            flags = t._3
            // streamId = t._4
            _ <- this.sendFrame(data_frame)
            x <- outDataQEventQ.take
            _ <- outDataQEventQ.take.whenA(
              x == true
            )
            _ <- outDataQEventQ
              .offer(true)
              .whenA(x == true) // rare case when shutdown requested interleaved with packet send which is false
            // _ <- IO.println("Yeld true").whenA(x == true) // we need to preserve true as flag to termnate fiber

            // up <- c.active.get
            // _ <- IO(streamTbl.remove(streamId)).whenA(up == false)

            _ <- c.done.complete(()).whenA(Flags.END_STREAM(flags) == true)

          } yield ()

        case None =>
          for {
            _ <- IO.unit
            // up <- c.active.get
            // _ <- c.done.complete(()).whenA(up == false)
            // x <- outDataQEventQ.take
            // _ <- outDataQEventQ.offer(x)
            // _ <- IO.println( "Yeld true1" ) //.whenA( x == true )

            // up <- c.active.get
            // _ <- IO.println("REM").whenA(up == false)
            // _ <- IO(streamTbl.remove(streamId)).whenA(up == false)

          } yield ()
      }
    } yield ()

  }

  //////////////////////////////////////////
  private[this] def streamDataOutWorker: IO[Boolean] = {
    for {
      x <- outDataQEventQ.take
      _ <- outDataQEventQ.offer(x)

      _ <- this.streamTbl.iterator.toSeq.foldMap[IO[Unit]](c => streamDataOutBoundProcessor(c._1, c._2))
      _ <- Logger[IO].debug("Shutdown H2 outbound data packet priority manager").whenA(x == true)
    } yield (x)
  }

  /*
    When the value of SETTINGS_INITIAL_WINDOW_SIZE changes, a receiver MUST adjust
    the size of all stream flow-control windows that it maintains by the
    difference between the new value and the old value.
   */
  private[this] def updateInitiallWindowSize(stream: Http2Stream, currentWinSize: Int, newWinSize: Int) = {
    Logger[IO].info(s"Http2Connection.upddateInitialWindowSize( $currentWinSize, $newWinSize)") >>
      stream.transmitWindow.update(txBytesLeft => newWinSize - (currentWinSize - txBytesLeft)) >> stream.outXFlowSync
        .offer(())
  }

  private[this] def upddateInitialWindowSizeAllStreams(currentSize: Int, newSize: Int) = {
    Logger[IO].trace(s"Http2Connection.upddateInitialWindowSizeAllStreams($currentSize, $newSize)") >>
      streamTbl.values.toSeq.traverse(stream => updateInitiallWindowSize(stream, currentSize, newSize)).void
  }

  private[this] def updateAndCheckGlobalTx(streamId: Int, inc: Int) = {
    for {
      _ <- globalTransmitWindow.update(_ + inc)
      rs <- globalTransmitWindow.get
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
                                    // _ <- IO.println("RS = " + rs)
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

  private[this] def handleStreamErrors(streamId: Int, e: Throwable): IO[Unit] = {
    e match {
      case e @ ErrorGen(streamId, code, name) =>
        Logger[IO].error("handleStreamErrors: " + e.toString) >>
          ch.write(Frames.mkGoAwayFrame(streamId, code, name.getBytes)).void >> this.ch.close()
      case _ => Logger[IO].error("handleStreamErrors:: " + e.toString) >> IO.raiseError(e)
    }
  }

  private[this] def interceptContentLen(c: Http2Stream, hdr: Headers) = {
    hdr.get("content-length") match {
      case Some(cl) =>
        c.contentLenFromHeader
          .complete(try { Some(cl.toInt) }
          catch case e: java.lang.NumberFormatException => None)
          .void

      case None => c.contentLenFromHeader.complete(None).void
    }
  }

  private[this] def openStream11(streamId: Int, request: Request): IO[Unit] = {
    for {
      nS <- IO(concurrentStreams.get)
      _ <- Logger[IO].info(s"Open upgraded http/1.1 stream: $streamId  total = ${streamTbl.size} active = ${nS}")

      d <- Deferred[IO, Headers] // start stream, after done with headers and continuations
      done <- Deferred[IO, Unit]
      trailingHdr <- Deferred[IO, Headers] // safe access to trailing header, only when they are fully ready

      contentLenFromHeader <- Deferred[IO, Option[Int]]

      header <- IO(ArrayBuffer.empty[ByteBuffer])
      trailing_header <- IO(ArrayBuffer.empty[ByteBuffer])

      dataOut <- Queue.bounded[IO, ByteBuffer](1) // up to MAX_CONCURRENT_STREAMS users
      xFlowSync <- Queue.unbounded[IO, Unit]
      dataIn <- Queue.unbounded[IO, ByteBuffer]
      transmitWindow <- Ref[IO].of[Long](settings_client.INITIAL_WINDOW_SIZE)

      localInboundWindowSize <- Ref[IO].of[Long](65535)

      updSyncQ <- Queue.dropping[IO, Unit](1)
      pendingInBytes <- Ref[IO].of(0)
      active <- Ref[IO].of(true)

      c <- IO(
        Http2Stream(
          active,
          d,
          header,
          trailing_header,
          inDataQ = dataIn,
          outDataQ = dataOut,
          outXFlowSync = xFlowSync,
          transmitWindow,
          updSyncQ,
          pendingInBytes,
          inboundWindow = localInboundWindowSize,
          contentLenFromHeader,
          trailingHdr,
          done
        )
      )

      _ <- IO(this.streamTbl.put(streamId, c))

      streamFork = route2(streamId, request)
      _ <- streamFork.handleErrorWith(e => handleStreamErrors(streamId, e)).start

    } yield ()

  }

  private[this] def openStream(streamId: Int, flags: Int) =
    for {

      // usedId <- usedStreamIdCounter.get
      // _ <- usedStreamIdCounter.set(streamId)

      // _ <- IO
      //  .raiseError(ErrorGen(streamId, Error.PROTOCOL_ERROR, "Sends a HEADERS frame on previously closed(used) stream"))
      //  .whenA(streamId <= usedId)

      nS <- IO(concurrentStreams.get)
      _ <- Logger[IO].debug(s"Open stream: $streamId  total = ${streamTbl.size} active = ${nS}")

      d <- Deferred[IO, Headers] // start stream, after done with headers and continuations
      done <- Deferred[IO, Unit]
      trailingHdr <- Deferred[IO, Headers] // safe access to trailing header, only when they are fully ready

      contentLenFromHeader <- Deferred[IO, Option[Int]]

      header <- IO(ArrayBuffer.empty[ByteBuffer])
      trailing_header <- IO(ArrayBuffer.empty[ByteBuffer])

      dataOut <- Queue.bounded[IO, ByteBuffer](1) // up to MAX_CONCURRENT_STREAMS users
      xFlowSync <- Queue.unbounded[IO, Unit]
      dataIn <- Queue.unbounded[IO, ByteBuffer]
      transmitWindow <- Ref[IO].of[Long](settings_client.INITIAL_WINDOW_SIZE)

      localInboundWindowSize <- Ref[IO].of[Long](INITIAL_WINDOW_SIZE)
      _ <- sendFrame(Frames.mkWindowUpdateFrame(streamId, INITIAL_WINDOW_SIZE - 65535))
        .whenA(INITIAL_WINDOW_SIZE > 65535L)
      _ <- Logger[IO]
        .debug(s"Send UPDATE WINDOW, streamId = $streamId: ${INITIAL_WINDOW_SIZE - 65535}")
        .whenA(INITIAL_WINDOW_SIZE > 65535L)

      updSyncQ <- Queue.dropping[IO, Unit](1)
      pendingInBytes <- Ref[IO].of(0)

      active <- Ref[IO].of(true)

      c <- IO(
        Http2Stream(
          active,
          d,
          header,
          trailing_header,
          inDataQ = dataIn,
          outDataQ = dataOut,
          outXFlowSync = xFlowSync,
          transmitWindow,
          updSyncQ,
          pendingInBytes,
          inboundWindow = localInboundWindowSize,
          contentLenFromHeader,
          trailingHdr,
          done
        )
      )

      _ <- IO(concurrentStreams.incrementAndGet())
      _ <- IO(this.streamTbl.put(streamId, c))

      streamFork = for {
        h <- d.get
        _ <- interceptContentLen(c, h)
        r <- IO(
          Request( id, streamId, 
            h,
            if ((flags & Flags.END_STREAM) == Flags.END_STREAM)
              Stream.empty
            else Http2Connection.makeDataStream(this, dataIn),
            trailingHdr
          )
        )
        _ <- route2(streamId, r)

      } yield ()
      _ <- streamFork.handleErrorWith(e => handleStreamErrors(streamId, e)).start
    } yield ()

  private[this] def checkForTrailingHeaders(streamId: Int, flags: Int): IO[Boolean] = {
    for {
      o <- IO(streamTbl.get(streamId)) // if already in the table, we process trailing headers.
      trailing <- o match {
        case Some(e) =>
          IO
            .raiseError(ErrorGen(streamId, Error.PROTOCOL_ERROR, "A second HEADERS frame without the END_STREAM flag"))
            .whenA(e.endFlag == false && e.endHeadersFlag == false) >>
            IO
              .raiseError(
                ErrorGen(
                  streamId,
                  Error.PROTOCOL_ERROR,
                  "A second (trailing?) HEADERS frame without the END_HEADER flag"
                )
              )
              .whenA(e.endFlag == true && e.endHeadersFlag == false) >>
            IO
              .raiseError(ErrorGen(streamId, Error.PROTOCOL_ERROR, "A second HEADERS frame on closed stream"))
              .whenA(e.endFlag == true && e.endHeadersFlag == true) >> IO(true)
        case None => IO(false)
      }
    } yield (trailing)
  }

  private[this] def doStreamHeaders(streamId: Int, flags: Int): IO[Boolean] = {
    for {
      trailing <- checkForTrailingHeaders(streamId, flags)
      _ <- openStream(streamId, flags).whenA(trailing == false)
    } yield (trailing)
  }

  private[this] def updateStreamWith(num: Int, streamId: Int, run: Http2Stream => IO[Unit]): IO[Unit] = {
    for {
      opt_D <- IO(streamTbl.get(streamId))
      _ <- opt_D match { // Option( null ) gives None
        case None =>
          Logger[IO].error(
            s"updateStreamWith() invalid streamId - $streamId, code=$num"
          ) >> IO.raiseError(ErrorGen(streamId, Error.PROTOCOL_ERROR, "invalid stream id"))
        case Some(con_rec) => run(con_rec)
      }
    } yield ()

  }

  private[this] def processInboundGlobalFlowControl(streamId: Int, dataSize: Int) = {
    for {
      win_sz <- this.globalInboundWindow.get

      pending_sz <- this.globalBytesOfPendingInboundData.get

      WINDOW <- IO(this.settings.INITIAL_WINDOW_SIZE)

      _ <-
        if ((win_sz - dataSize) < WINDOW * 0.3) { // less then 30% space available, time for WINDOW_UPDATE
          val updWin =
            if (WINDOW > pending_sz) WINDOW - pending_sz else WINDOW
          this.globalInboundWindow.update(_ + updWin) >> Logger[IO].trace(
            "Send WINDOW UPDATE global = " + updWin
          ) >> sendFrame(
            Frames.mkWindowUpdateFrame(0, updWin)
          )
        } else IO.unit
    } yield ()
  }

  private[this] def accumData(streamId: Int, bb: ByteBuffer, dataSize: Int): IO[Unit] = {
    for {
      o_c <- IO(this.streamTbl.get(streamId))
      _ <- IO.raiseError(ErrorGen(streamId, Error.FRAME_SIZE_ERROR, "invalid stream id")).whenA(o_c.isEmpty)
      c <- IO(o_c.get)
      _ <- IO(c.contentLenFromDataFrames += dataSize)

      localWin_sz <- c.inboundWindow.get
      _ <- this.globalInboundWindow.update(_ - dataSize) >>
        this.incrementGlobalPendingInboundData(dataSize) >>
        processInboundGlobalFlowControl(streamId, dataSize) >>
        c.inboundWindow.update(_ - dataSize) >>
        c.bytesOfPendingInboundData.update(_ + dataSize)

      _ <- c.inDataQ.offer(bb)

    } yield ()

  }

  private[this] def accumHeaders(streamId: Int, bb: ByteBuffer): IO[Unit] =
    updateStreamWith(2, streamId, c => IO(c.header.addOne(bb)))

  private[this] def accumTrailingHeaders(streamId: Int, bb: ByteBuffer): IO[Unit] =
    updateStreamWith(3, streamId, c => IO(c.trailing_header.addOne(bb)))

  private[this] def finalizeTrailingHeaders(streamId: Int): IO[Unit] = {
    updateStreamWith(
      4,
      streamId,
      c =>
        for {
          // close data stream, which is stuck without END_STREAM due to addion of trailing header.
          _ <- c.inDataQ.offer(Frames.mkDataFrame(streamId, true, 0, ByteBuffer.allocate(0)))
          http_headers <- IO(headerDecoder.decodeHeaders(c.trailing_header.toSeq))
          _ <- c.trailingHeader.complete(http_headers)
        } yield ()
    )
  }

  private[this] def setEmptyTrailingHeaders(streamId: Int): IO[Unit] = {
    updateStreamWith(6, streamId, c => c.trailingHeader.complete(Headers()).void)
  }

  private[this] def triggerStream(streamId: Int): IO[Unit] = {
    updateStreamWith(
      5,
      streamId,
      c =>
        for {
          headers <- IO(headerDecoder.decodeHeaders(c.header.toSeq))
          _ <- c.d.complete(headers).void
        } yield ()
    )

  }

  private[this] def markEndOfHeaders(streamId: Int): IO[Unit] =
    updateStreamWith(7, streamId, c => IO { c.endHeadersFlag = true })

  private[this] def markEndOfStream(streamId: Int): IO[Unit] =
    updateStreamWith(8, streamId, c => IO { c.endFlag = true })

  private[this] def markEndOfStreamWithData(streamId: Int): IO[Unit] =
    updateStreamWith(
      9,
      streamId,
      c =>
        for {
          _ <- IO { c.endFlag = true }
          contentLenFromHeader <- c.contentLenFromHeader.get
          _ <- IO
            .raiseError(
              ErrorGen(
                streamId,
                Error.PROTOCOL_ERROR,
                "HEADERS frame with the content-length header field which does not equal the DATA frame payload length"
              )
            )
            .whenA(contentLenFromHeader.isDefined && c.contentLenFromDataFrames != contentLenFromHeader.get)

        } yield ()
    )

  private[this] def haveHeadersEnded(streamId: Int): IO[Boolean] = {
    for {
      opt <- IO(streamTbl.get(streamId))
      b <- opt match {
        case Some(s0) => IO(s0.endHeadersFlag)
        case None     => IO.raiseError(ErrorGen(streamId, Error.FRAME_SIZE_ERROR, "invalid stream id"))
      }
    } yield (b)
  }

  private[this] def hasEnded(streamId: Int): IO[Boolean] = {
    for {
      opt <- IO(streamTbl.get(streamId))
      b <- opt match {
        case Some(s0) => IO(s0.endFlag)
        case None     => IO.raiseError(ErrorGen(streamId, Error.FRAME_SIZE_ERROR, "invalid stream id"))
      }
    } yield (b)
  }

  case class txWindow_SplitDataFrame(buffer: ByteBuffer, dataLen: Int)

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

      val dataFrame1 = Frames.mkDataFrame(streamId, false, padding = 0, ByteBuffer.wrap(buf0))
      val dataFrame2 = Frames.mkDataFrame(streamId, false, padding = 0, bb)

      (
        txWindow_SplitDataFrame(dataFrame1, requiredLen.toInt),
        Some(txWindow_SplitDataFrame(dataFrame2, len - requiredLen.toInt))
      )

    } else (txWindow_SplitDataFrame(original_bb, len), None)
  }

  private[this] def txWindow_Transmit(stream: Http2Stream, bb: ByteBuffer, data_len: Int): IO[Long] = {
    for {
      tx_g <- globalTransmitWindow.get
      tx_l <- stream.transmitWindow.get
      bytesCredit <- IO(Math.min(tx_g, tx_l))

      _ <-
        if (bytesCredit > 0)
          (for {
            rlen <- IO(Math.min(bytesCredit, data_len))
            frames <- IO(splitDataFrames(bb, rlen))

            // _ <- outDataQEventQ.offer(false) >> stream.outDataQ.offer(frames._1.buffer)

            _ <-
              if (Http2Connection.FAST_MODE == true) sendFrame(frames._1.buffer)
              else stream.outDataQ.offer(frames._1.buffer) >> outDataQEventQ.offer(false)

            _ <- globalTransmitWindow.update(_ - rlen)
            _ <- stream.transmitWindow.update(_ - rlen)

            _ <- frames._2 match {
              case Some(f0) =>
                stream.outXFlowSync.take >> txWindow_Transmit(stream, f0.buffer, f0.dataLen)
              case None => IO.unit
            }

          } yield ())
        else stream.outXFlowSync.take >> txWindow_Transmit(stream, bb, data_len)

    } yield (bytesCredit)
  }

  def sendDataFrame(streamId: Int, bb: ByteBuffer): IO[Unit] =
    for {
      t <- IO(Http2Connection.parseFrame(bb))
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

  def sendFrame(b: ByteBuffer) = outq.offer(b)
  ////////////////////////////////////////////

  def route(request: Request): IO[Response] = for {

    v <- request.stream.compile.toVector

    stringMessage <- IO(new String(v.toArray))
    _ <- Logger[IO].debug(s"route: RECEIVEDt: ${stringMessage.length} bytes")
    rep <- IO(Response.Ok().asText("Hello World from Oleg Strigun").contentType(ContentType.Plain))

  } yield (rep)

  def route2(streamId: Int, request: Request): IO[Unit] = {

    val T = for {
      _ <- Logger[IO].debug(s"Processing request for stream = $streamId ${request.method.name} ${request.path} ")
      _ <- Logger[IO].trace("request.headers: " + request.headers.printHeaders(" | "))

      _ <- IO
        .raiseError(ErrorGen(streamId, Error.COMPRESSION_ERROR, "empty headers: COMPRESSION_ERROR"))
        .whenA(request.headers.tbl.size == 0)

      _ <- IO
        .raiseError(ErrorGen(streamId, Error.PROTOCOL_ERROR, "Upercase letters in the header keys"))
        .whenA(request.headers.ensureLowerCase == false)

      _ <- IO
        .raiseError(ErrorGen(streamId, Error.PROTOCOL_ERROR, "Invalid pseudo-header field"))
        .whenA(request.headers.validatePseudoHeaders == false)

      _ <- IO
        .raiseError(ErrorGen(streamId, Error.PROTOCOL_ERROR, "Connection-specific header field forbidden"))
        .whenA(request.headers.get("connection").isDefined == true)

      _ <- IO
        .raiseError(ErrorGen(streamId, Error.PROTOCOL_ERROR, "TE header field with any value other than trailers"))
        .whenA(request.headers.get("te").isDefined && request.headers.get("te").get != "trailers")

      response_o <- (httpRoute(request)).handleErrorWith {
        case e: java.io.FileNotFoundException =>
          Logger[IO].error(e.toString) >> IO(None)
        case e =>
          Logger[IO].error(e.toString) >>
            IO(Some(Response.Error(StatusCode.InternalServerError)))
      }

      _ <- response_o match {
        case Some(response) =>
          for {
            _ <- Logger[IO].trace("response.headers: " + response.headers.printHeaders(" | "))
            endStreamInHeaders <- if (response.stream == Stream.empty) IO(true) else IO(false)
            _ <- Logger[IO].debug(
              s"Send response code: ${response.code.toString()} only header = $endStreamInHeaders"
            )
            _ <- hSem.acquire.bracket { _ =>
              headerFrame(streamId, Priority.NoPriority, endStreamInHeaders, response.headers)
                .traverse(b => sendFrame(b))
            }(_ => hSem.release)

            pref <- Ref.of[IO, Chunk[Byte]](Chunk.empty[Byte])

            _ <- response.stream.chunks
              .foreach { chunk =>
                for {
                  chunk0 <- pref.get
                  _ <- dataFrame(streamId, false, chunk0.toByteBuffer)
                    .traverse(b => sendDataFrame(streamId, b))
                    .whenA(chunk0.nonEmpty)
                  _ <- pref.set(chunk)
                } yield ()

              }
              .compile
              .drain
              .whenA(endStreamInHeaders == false)

            lastChunk <- pref.get
            _ <- dataFrame(streamId, true, lastChunk.toByteBuffer)
              .traverse(b => sendDataFrame(streamId, b))
              .whenA(endStreamInHeaders == false)
              .void

            _ <- updateStreamWith(10, streamId, c => c.done.complete(()).void).whenA(endStreamInHeaders == true)

          } yield ()

        case None =>
          for {
            o44 <- IO(Response.Error(StatusCode.NotFound)) // 404
            _ <- Logger[IO].trace("response.headers: " + o44.headers.printHeaders(" | "))
            _ <- Logger[IO].debug(s"Send response code: ${o44.code.toString()}")
            _ <- hSem.acquire.bracket { _ =>
              for {
                bb2 <- IO(headerFrame(streamId, Priority.NoPriority, true, o44.headers))
                _ <- bb2.traverse(b => sendFrame(b))
                _ <- updateStreamWith(10, streamId, c => c.done.complete(()).void)

              } yield ()
            }(_ => hSem.release)
          } yield ()
      }
      _ <- closeStream(streamId)
    } yield ()

    T

    // IO(T).bracket( c => c )(_ => closeStream(streamId))
  }

  def closeStream(streamId: Int): IO[Unit] = {
    for {
      _ <- updateStreamWith(12, streamId, c => c.done.get).whenA(Http2Connection.FAST_MODE == false)
      _ <- IO(concurrentStreams.decrementAndGet())
      // keep last HTTP2_MAX_CONCURRENT_STREAMS in closed state.
      // potentialy it's possible for client to reuse old streamId outside of that last MAX
      // _ <- IO(streamTbl.remove(streamId - 2 * Http2Connection.HTTP2_MAX_CONCURRENT_STREAMS))
      //  .whenA(streamId > 2 * Http2Connection.HTTP2_MAX_CONCURRENT_STREAMS)
      // _ <- IO.sleep( 50.millis )
      _ <- IO(streamTbl.remove(streamId))
      _ <- Logger[IO].debug(s"Close stream: $streamId")
    } yield ()

  }

  /** Generate stream data frame(s) for the specified data
    *
    * If the data exceeds the peers MAX_FRAME_SIZE setting, it is fragmented into a series of frames.
    */
  def dataFrame(
      streamId: Int,
      endStream: Boolean,
      data: ByteBuffer
  ): scala.collection.immutable.Seq[ByteBuffer] = {
    val limit =
      settings.MAX_FRAME_SIZE - 128

    if (data.remaining <= limit) {

      val acc = new ArrayBuffer[ByteBuffer]
      acc.addOne(Frames.mkDataFrame(streamId, endStream, padding = 0, data))

      acc.toSeq
    } else { // need to fragment
      // println("FRAGMENT - SHOULD NOT BE THERE")
      val acc = new ArrayBuffer[ByteBuffer]

      while (data.hasRemaining) {

        val len = math.min(data.remaining(), limit)

        val cur_pos = data.position()

        val thisData = data.slice.limit(len)

        data.position(cur_pos + len)

        val eos = endStream && !data.hasRemaining
        acc.addOne(Frames.mkDataFrame(streamId, eos, padding = 0, thisData))
      }
      // acc.foreach(c => println("------>" + c.limit()))
      acc.toSeq
    }
  }

  def takeSlice(buf: ByteBuffer, len: Int): ByteBuffer = {
    val head = buf.slice.limit(len)
    buf.position(len)
    head
  }

  /** Generate stream header frames from the provided header sequence
    *
    * If the compressed representation of the headers exceeds the MAX_FRAME_SIZE setting of the peer, it will be broken
    * into a HEADERS frame and a series of CONTINUATION frames.
    */
  // BROKEN TODO TODO!!!
  //////////////////////////////
  def headerFrame( // TODOD pbly not working for multi-packs
      streamId: Int,
      priority: Priority,
      endStream: Boolean,
      headers: Headers
  ): scala.collection.immutable.Seq[ByteBuffer] = {
    val rawHeaders = headerEncoder.encodeHeaders(headers)

    val limit = settings.MAX_FRAME_SIZE - 61

    val headersPrioritySize =
      if (priority.isDefined) 5 else 0 // priority(4) + weight(1), padding = 0

    if (rawHeaders.remaining() + headersPrioritySize <= limit) {
      val acc = new ArrayBuffer[ByteBuffer]
      acc.addOne(Frames.mkHeaderFrame(streamId, priority, endHeaders = true, endStream, padding = 0, rawHeaders))

      acc.toSeq
    } else {
      // need to fragment
      val acc = new ArrayBuffer[ByteBuffer]

      val headersBuf = takeSlice(rawHeaders, limit - headersPrioritySize)
      acc += Frames.mkHeaderFrame(streamId, priority, endHeaders = false, endStream, padding = 0, headersBuf)

      while (rawHeaders.hasRemaining) {
        val size = math.min(limit, rawHeaders.remaining)
        val continueBuf = takeSlice(rawHeaders, size)
        val endHeaders = !rawHeaders.hasRemaining
        acc += Frames.mkContinuationFrame(streamId, endHeaders, continueBuf)
      }
      acc.toSeq
    }
  }

  def processIncoming(leftOver: Chunk[Byte]): IO[Unit] = (for {
    _ <- Logger[IO].trace(s"Http2Connection.processIncoming() leftOver= ${leftOver.size}")
    _ <- Http2Connection
      .makePacketStream(ch, HTTP2_KEEP_ALIVE_MS, leftOver)
      .foreach(packet => { packet_handler(httpReq11, packet) })
      .compile
      .drain
  } yield ()).handleErrorWith[Unit] {
    case e @ ErrorGen(streamId, code, name) =>
      Logger[IO].error(s"Http2Connnection.processIncoming() ${e.code} ${name}") >>
        sendFrame(Frames.mkGoAwayFrame(streamId, code, name.getBytes))
    case e @ _ => {
      Logger[IO].error(e.toString()) // >> */IO.raiseError(e)
    }
  }

  ////////////////////////////////////////////////////
  def packet_handler(
      http11request: Ref[IO, Option[Request]],
      packet: Chunk[Byte]
  ): IO[Unit] = {

    val buffer = packet.toByteBuffer
    val packet0 = buffer.slice // preserve reference to whole packet

    val len = Frames.getLengthField(buffer)
    val frameType = buffer.get()
    val flags = buffer.get()
    val streamId = Frames.getStreamId(buffer)
    for {
      _ <- Logger[IO].debug(s"frametype=$frameType with streamId=$streamId len=$len flags=$flags")
      _ <-
        if (len > settings.MAX_FRAME_SIZE)
          IO.raiseError(ErrorGen(streamId, Error.FRAME_SIZE_ERROR, "HEADERS exceeds SETTINGS_MAX_FRAME_SIZE"))
        else if (streamId != 0 && streamId % 2 == 0)
          IO.raiseError(ErrorGen(streamId, Error.PROTOCOL_ERROR, "even-numbered stream identifier"))
        else {
          frameType match {
            case FrameTypes.RST_STREAM =>
              val code: Long = buffer.getInt() & Masks.INT32 // java doesn't have unsigned integers
              for {
                _ <- IO
                  .raiseError(ErrorGen(streamId, Error.FRAME_SIZE_ERROR, "RST_STREAM: FRAME_SIZE_ERROR"))
                  .whenA(len != 4)
                o_s <- IO(this.streamTbl.get(streamId))
                _ <- Logger[IO].info(s"Reset Stream $streamId present=${o_s.isDefined} code=$code")
                _ <- IO
                  .raiseError(
                    ErrorGen(
                      streamId,
                      Error.PROTOCOL_ERROR,
                      s"Reset Stream $streamId present=${o_s.isDefined} code=$code"
                    )
                  )
                  .whenA(o_s.isEmpty)

                _ <- markEndOfStream(streamId)

                // _ <- updateStreamWith(100, streamId, c => c.done.complete(()).void).whenA(o_s.isDefined)
                // _ <- closeStream(streamId).whenA(o_s.isDefined)
                // just abort wait and go with norlmal close()
                // _ <- updateStreamWith(78, streamId, c => c.done.complete(()).void).whenA(o_s.isDefined)
              } yield ()

            case FrameTypes.HEADERS =>
              val padLen: Byte = if ((flags & Flags.PADDED) != 0) buffer.get() else 0 // advance one byte padding len 1

              val priority = if ((flags & Flags.PRIORITY) != 0) {
                val rawInt = buffer.getInt();
                val weight = buffer.get()

                val dependentID = Flags.DepID(rawInt)
                val exclusive = Flags.DepExclusive(rawInt)
                Some(dependentID, exclusive, weight)
              } else None
              val lim = buffer.limit() - padLen
              buffer.limit(lim)
              if (headerStreamId == 0) headerStreamId = streamId
              if (headerStreamId != streamId)
                IO.raiseError(
                  ErrorGen(
                    streamId,
                    Error.PROTOCOL_ERROR,
                    "HEADERS frame to another stream while sending the header blocks"
                  )
                )
              else {
                for {
                  _ <- IO
                    .raiseError(ErrorGen(streamId, Error.PROTOCOL_ERROR, "streamId is 0 for HEADER"))
                    .whenA(streamId == 0)
                  _ <- IO
                    .raiseError(
                      ErrorGen(
                        streamId,
                        Error.PROTOCOL_ERROR,
                        "stream's Id number is less than previously used Id number"
                      )
                    )
                    .whenA(lastStreamId != 0 && lastStreamId > streamId)
                  _ <- IO { lastStreamId = streamId }

                  _ <- priority match {
                    case Some(t3) =>
                      IO.raiseError(ErrorGen(streamId, Error.PROTOCOL_ERROR, "HEADERS frame depends on itself"))
                        .whenA(t3._1 == streamId)
                    case None => IO.unit
                  }

                  // total <- IO(this.streamTbl.size)
                  total <- IO(this.concurrentStreams.get())

                  _ <- IO
                    .raiseError(
                      ErrorGen(
                        streamId,
                        Error.PROTOCOL_ERROR,
                        "MAX_CONCURRENT_STREAMS exceeded, with total streams = " + total
                      )
                    )
                    .whenA(total >= settings.MAX_CONCURRENT_STREAMS)

                  o_s <- IO(this.streamTbl.get(streamId))
                  _ <- o_s match {
                    case Some(s) =>
                      IO.raiseError(ErrorGen(streamId, Error.STREAM_CLOSED, "STREAM_CLOSED")).whenA(s.endFlag)
                    case None => IO.unit
                  }
                  trailing <- doStreamHeaders(streamId, flags)
                  _ <- Logger[IO].debug(s"trailing headers: $trailing").whenA(trailing == true)
                  // currently cannot do trailing without END_STREAM ( no continuation for trailing, seems this is stated in RFC, spec test requires it)
                  _ <- IO
                    .raiseError(
                      ErrorGen(streamId, Error.INTERNAL_ERROR, "Second HEADERS frame without the END_STREAM flag")
                    )
                    .whenA(((flags & Flags.END_STREAM) == 0) && trailing)

                  _ <- accumHeaders(streamId, buffer).whenA(trailing == false)
                  _ <- accumTrailingHeaders(streamId, buffer).whenA(trailing == true)

                  _ <- markEndOfStream(streamId).whenA((flags & Flags.END_STREAM) != 0)
                  _ <- markEndOfHeaders(streamId).whenA((flags & Flags.END_HEADERS) != 0)

                  // if no body reset trailing headers to empty
                  _ <- setEmptyTrailingHeaders(streamId).whenA(((flags & Flags.END_STREAM) != 0) && trailing == false)

                  _ <- triggerStream(streamId).whenA(((flags & Flags.END_HEADERS) != 0) && (trailing == false))

                  _ <- finalizeTrailingHeaders(streamId).whenA((flags & Flags.END_HEADERS) != 0 && trailing == true)

                  _ <- IO { headerStreamId = 0 }.whenA((flags & Flags.END_HEADERS) != 0) // ready to tak new stream

                } yield ()
              }

            case FrameTypes.CONTINUATION =>
              // TODO: CONTINUATION for trailing headers not supported yet.
              for {
                b1 <- haveHeadersEnded(streamId)
                _ <- IO.raiseError(ErrorGen(streamId, Error.PROTOCOL_ERROR, "END HEADERS")).whenA(b1)
                _ <- markEndOfHeaders(streamId).whenA((flags & Flags.END_HEADERS) != 0)
                _ <- markEndOfStream(streamId).whenA((flags & Flags.END_STREAM) != 0)
                _ <- accumHeaders(streamId, buffer)
                _ <- triggerStream(streamId).whenA((flags & Flags.END_HEADERS) != 0)

              } yield ()

            case FrameTypes.DATA =>
              // DATA padLen = 0, len= 7, limit=16
              // val true_padding = buffer.limit() - len - Constants.HeaderSize
              // val true_padding = packet.size - len - Constants.HeaderSize
              val padLen: Byte = if ((flags & Flags.PADDED) != 0) buffer.get() else 0
              val padByte = if ((flags & Flags.PADDED) != 0) 1 else 0
              // println(
              //  "DATA padLen = " + padLen + ", len= " + len + ", packet.size=" + packet.size + " padByte = " + padByte + "Constants.HeaderSize =" + Constants.HeaderSize
              // )
              for {
                headersEnded <- haveHeadersEnded(streamId)
                closed <- hasEnded(streamId)

                t1: Long <- IO(packet.size.toLong - padLen - Constants.HeaderSize - padByte)
                t2: Long <- IO(len.toLong - padByte - padLen)
                _ <- IO
                  .raiseError(ErrorGen(streamId, Error.PROTOCOL_ERROR, "DATA frame with invalid pad length"))
                  .whenA(t1 != t2)

                _ <- IO
                  .raiseError(ErrorGen(streamId, Error.PROTOCOL_ERROR, "CON or HEADERS not finished"))
                  .whenA(headersEnded == false)

                b <- hasEnded(streamId)
                _ <- IO.raiseError(ErrorGen(streamId, Error.STREAM_CLOSED, "STREAM_CLOSED")).whenA(b)
                _ <- accumData(streamId, packet0, len)
                _ <- markEndOfStreamWithData(streamId).whenA((flags & Flags.END_STREAM) != 0)
                // streams ends with data, no trailing headers for sure, reset to empty
                _ <- setEmptyTrailingHeaders(streamId).whenA(((flags & Flags.END_STREAM) != 0))
              } yield ()

            case FrameTypes.WINDOW_UPDATE => {
              val increment = buffer.getInt() & Masks.INT31
              Logger[IO].debug(s"WINDOW_UPDATE $increment $streamId") >> this
                .updateWindow(streamId, increment)
                .handleErrorWith[Unit] {
                  case e @ ErrorRst(streamId, code, name) =>
                    Logger[IO].error("Reset frane") >> sendFrame(Frames.mkRstStreamFrame(streamId, code))
                  case e @ _ => IO.raiseError(e)
                }
            }

            case FrameTypes.PING =>
              var data = new Array[Byte](8)
              buffer.get(data)
              if ((flags & Flags.ACK) == 0) {
                for {
                  _ <- IO
                    .raiseError(ErrorGen(streamId, Error.PROTOCOL_ERROR, "Ping streamId not 0"))
                    .whenA(streamId != 0)
                  _ <- sendFrame(Frames.mkPingFrame(ack = true, data))
                } yield ()
              } else IO.unit // else if (this.start)

            case FrameTypes.GOAWAY =>
              IO.raiseError(
                ErrorGen(streamId, Error.PROTOCOL_ERROR, "GOAWAY frame with a stream identifier other than 0x0")
              ).whenA(streamId != 0) >>
                IO {
                  val lastStream = Flags.DepID(buffer.getInt())
                  val code: Long =
                    buffer.getInt() & Masks.INT32 // java doesn't have unsigned integers
                  val data = new Array[Byte](buffer.remaining)
                  buffer.get(data)
                  data
                }.flatMap(data =>
                  IO.raiseError(ErrorGen(streamId, Error.PROTOCOL_ERROR, "GOAWAY frame received " + new String(data)))
                )

            case FrameTypes.PRIORITY =>
              val rawInt = buffer.getInt();
              val weight = buffer.get()
              val dependentId = Flags.DepID(rawInt)
              val exclusive = Flags.DepExclusive(rawInt)

              for {
                _ <- Logger[IO].debug("PRIORITY frane received")
                _ <- IO
                  .raiseError(
                    ErrorGen(
                      streamId,
                      Error.PROTOCOL_ERROR,
                      "PRIORITY frame to another stream while sending the headers blocks"
                    )
                  )
                  .whenA(headerStreamId != 0)
                _ <- IO
                  .raiseError(ErrorGen(streamId, Error.PROTOCOL_ERROR, "PRIORITY frame with 0x0 stream identifier"))
                  .whenA(streamId == 0)
                _ <- IO
                  .raiseError(
                    ErrorGen(streamId, Error.FRAME_SIZE_ERROR, "PRIORITY frame with a length other than 5 octets")
                  )
                  .whenA(len != 5)
                _ <- IO
                  .raiseError(ErrorGen(streamId, Error.PROTOCOL_ERROR, "PRIORITY frame depends on itself"))
                  .whenA(streamId == dependentId)
              } yield ()

            /* When the value of SETTINGS_INITIAL_WINDOW_SIZE changes, a receiver MUST adjust
          the size of all stream flow-control windows that it maintains by the
           difference between the new value and the old value.
             */

            case FrameTypes.PUSH_PROMISE =>
              IO.raiseError(ErrorGen(streamId, Error.PROTOCOL_ERROR, "PUSH_PROMISE frame"))

            case FrameTypes.SETTINGS =>
              (for {
                _ <- IO
                  .raiseError(
                    ErrorGen(
                      streamId,
                      Error.PROTOCOL_ERROR,
                      "ends a SETTINGS frame with a length other than a multiple of 6 octets"
                    )
                  )
                  .whenA(len % 6 != 0)

                _ <- IO
                  .raiseError(ErrorGen(streamId, Error.PROTOCOL_ERROR, "SETTINGS frame with ACK flag and payload"))
                  .whenA(len > 0 && Flags.ACK(flags))

                _ <- IO
                  .raiseError(
                    ErrorGen(streamId, Error.PROTOCOL_ERROR, "SETTINGS frame with a stream identifier other than 0x0")
                  )
                  .whenA(streamId != 0)

                _ <-
                  if (Flags.ACK(flags) == false) {
                    for {
                      res <- IO(Http2Settings.fromSettingsArray(buffer, len)) // <<<<<<<<<<<<<<<<<
                        .onError { case e: scala.MatchError =>
                          sendFrame(Frames.mkPingFrame(ack = true, Array.fill[Byte](8)(0x0)))
                        }

                      _ <- IO
                        .raiseError(
                          ErrorGen(
                            streamId,
                            Error.PROTOCOL_ERROR,
                            "SETTINGS_MAX_FRAME_SIZE (0x5): Sends the value below the initial value"
                          )
                        )
                        .whenA(settings_done == true && res.MAX_FRAME_SIZE < settings_client.MAX_FRAME_SIZE)

                      _ <- IO
                        .raiseError(
                          ErrorGen(
                            streamId,
                            Error.PROTOCOL_ERROR,
                            "SETTINGS_MAX_FRAME_SIZE (0x5): Sends the value above the maximum allowed frame size"
                          )
                        )
                        .whenA(res.MAX_FRAME_SIZE > 0xffffff)

                      _ <- IO
                        .raiseError(
                          ErrorGen(
                            streamId,
                            Error.PROTOCOL_ERROR,
                            "SETTINGS_ENABLE_PUSH (0x2): Sends the value other than 0 or 1"
                          )
                        )
                        .whenA(res.ENABLE_PUSH != 1 && res.ENABLE_PUSH != 0)

                      _ <- IO
                        .raiseError(
                          ErrorGen(
                            streamId,
                            Error.FLOW_CONTROL_ERROR,
                            "SETTINGS_INITIAL_WINDOW_SIZE (0x4): Sends the value above the maximum flow control window size"
                          )
                        )
                        .whenA((res.INITIAL_WINDOW_SIZE & Masks.INT32) > Integer.MAX_VALUE)

                      ws <- IO(this.settings_client.INITIAL_WINDOW_SIZE)
                      _ <- IO(Http2Settings.copy(this.settings_client, res))

                      _ <- upddateInitialWindowSizeAllStreams(ws, res.INITIAL_WINDOW_SIZE)
                      _ <- IO(this.settings.MAX_CONCURRENT_STREAMS = this.MAX_CONCURRENT_STREAMS)
                      _ <- IO(this.settings.INITIAL_WINDOW_SIZE = this.INITIAL_WINDOW_SIZE)

                      _ <- Logger[IO].debug(s"Remote INITIAL_WINDOW_SIZE ${this.settings_client.INITIAL_WINDOW_SIZE}")
                      _ <- Logger[IO].debug(s"Server INITIAL_WINDOW_SIZE ${this.settings.INITIAL_WINDOW_SIZE}")

                      _ <- sendFrame(Frames.makeSettingsFrame(ack = false, this.settings)).whenA(settings_done == false)
                      _ <- sendFrame(Frames.makeSettingsAckFrame())

                      // re-adjust inbound window if exceeds default
                      _ <- this.globalInboundWindow.set(INITIAL_WINDOW_SIZE)
                      _ <- sendFrame(Frames.mkWindowUpdateFrame(streamId, INITIAL_WINDOW_SIZE - 65535)).whenA(
                        INITIAL_WINDOW_SIZE > 65535L
                      )
                      _ <- Logger[IO]
                        .debug(s"Send UPDATE WINDOW global: ${INITIAL_WINDOW_SIZE - 65535}")
                        .whenA(INITIAL_WINDOW_SIZE > 65535L)

                      _ <- IO {
                        if (settings_done == false) settings_done = true
                      }

                    } yield ()
                  } else
                    (IO { start = false } >> http11request.get.flatMap {
                      case Some(x) => {
                        val stream = x.stream
                        val th = x.trailingHeaders
                        val h = x.headers.drop("connection")
                        this.openStream11(1, Request(id, 1, h, stream, th))
                      }
                      case None => IO.unit
                    }).whenA(start)

              } yield ()).handleErrorWith {
                case _: scala.MatchError => Logger[IO].error("Settings match error") >> IO.unit
                case e @ _               => IO.raiseError(e)
              }

            case _ =>
              for {
                _ <- IO
                  .raiseError(
                    ErrorGen(
                      streamId,
                      Error.PROTOCOL_ERROR,
                      "Sends an unknown extension frame in the middle of a header block"
                    )
                  )
                  .whenA(headerStreamId != 0)

              } yield ()

          }
        }
    } yield ()
  }

}
