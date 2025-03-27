package io.quartz.netio

import java.util.concurrent.locks.ReentrantLock
import java.net.SocketAddress
import java.util.function.{Consumer, BiConsumer}
import java.nio.ByteBuffer
import cats.effect.IO
import cats.effect.Ref
import fs2.Chunk
import java.util.concurrent.atomic.AtomicInteger
import io.quartz.iouring.{IoUringServerSocket, IoUringSocket, IoUring}
import scala.concurrent.ExecutionContextExecutorService
import cats.implicits._
import scala.concurrent.duration._

object IOURingChannel {

  private def ioUringAccept(
      ring: IoUring,
      serverSocket: IoUringServerSocket,
      cb: (ring: IoUring, socket: IoUringSocket) => Unit
  ) = {

    val bconsumer = new BiConsumer[IoUring, IoUringSocket] {
      override def accept(ring: IoUring, socket: IoUringSocket): Unit = {
        cb(ring, socket)
      }
    }
    serverSocket.onAccept(bconsumer)
    ring.queueAccept(serverSocket)
    ring.execute()
  }

  def accept(
      ring: IoUring,
      serverSocket: IoUringServerSocket
  ): IO[(IoUring, IoUringSocket)] = {
    for {
      result <- IO.async[(IoUring, IoUringSocket)](cb =>
        for {
          f1 <- IO((ring: IoUring, socket: IoUringSocket) => cb(Right((ring, socket))))
          _ <- IO(ioUringAccept(ring, serverSocket, f1))
        } yield (Some(IO.unit))
      )
    } yield (result)
  }

}

class IOURingChannel(
    val ring: IoUringEntry,
    val ch1: IoUringSocket,
    var timeOutMs: Long
) extends IOChannel {

  var f_putBack: ByteBuffer = null

  // val f_wRef = AtomicInteger(0)

  def put(bb: ByteBuffer): IO[Unit] = IO { f_putBack = bb }

  val lock = new ReentrantLock()

  def toDirectBuffer(buffer: ByteBuffer): ByteBuffer = {
    if (buffer.isDirect()) {
      return buffer; // Already a direct buffer
    }
    // Create a new direct buffer with the same capacity
    val directBuffer = ByteBuffer.allocateDirect(buffer.capacity());
    // Save the original position and limit
    val position = buffer.position();
    val limit = buffer.limit();
    // Copy the data
    buffer.rewind();
    directBuffer.put(buffer);
    // Restore the original position and limit for both buffers
    buffer.position(position);
    buffer.limit(limit);
    directBuffer.position(position);
    directBuffer.limit(limit);

    directBuffer;
  }

  def effectAsyncChannelIO(ring: IoUringEntry, ch: IoUringSocket)(
      op: (ring: IoUringEntry, ch: IoUringSocket) => (
          ByteBuffer => Unit
      ) => IO[Any]
  ) = {
    IO.async[ByteBuffer](cb =>
      IO(op(ring, ch)).flatMap(handler => {
        val f1: ByteBuffer => Unit = bb => {
          if (bb == null) {
            cb(Left(new java.nio.channels.ClosedChannelException()))
          } else if (bb.position() == 0 && bb.capacity() > 0) {
            cb(Left(new java.nio.channels.ClosedChannelException()))
          } else cb(Right(bb))
        }
        handler(f1) *> IO(Some(IO.unit))
      })
    )
  }

  private def ioUringReadIO(
      ring: IoUringEntry,
      ch: IoUringSocket,
      bufferDirect: ByteBuffer,
      cb: ByteBuffer => Unit
  ): IO[Unit] = {

    for {
      consumer <-
        IO(new Consumer[ByteBuffer] {
          override def accept(buffer: ByteBuffer): Unit = {
            cb(buffer)
          }
        })
      _ <- ring.queueRead(consumer, ch, bufferDirect)
    } yield ()
  }

  /**
   * Performs an asynchronous write operation using io_uring
   * @param ring The IOURing entry to use for the operation
   * @param ch The socket channel to write to
   * @param bufferDirect The direct ByteBuffer containing data to write
   * @param cb Callback function to handle the write completion
   * @return IO[Unit] representing the queued write operation
   */
  private def ioUringWriteIO(
      ring: IoUringEntry,
      ch: IoUringSocket,
      bufferDirect: ByteBuffer,
      cb: ByteBuffer => Unit
  ): IO[Unit] = {
    for {
      consumer <- IO(new Consumer[ByteBuffer] {
        override def accept(buffer: ByteBuffer): Unit = {
           cb(buffer)
        }
      })
      _ <- ring.queueWrite(consumer, ch, /*toDirectBuffer(*/ bufferDirect)
    } yield ()
  }

  private def submit(ring: IoUring) = {
    this.synchronized {
      ring.submit()
    }
  }

  def readBuffer(
      dst: ByteBuffer,
      timeOut: Int
  ): IO[Int] = {
    for {
      _ <-
        if (f_putBack != null) {
          IO(dst.put(f_putBack)) >> IO { f_putBack = null }
        } else IO.unit
      b1 <- effectAsyncChannelIO(ring, ch1)((ring, ch1) => ioUringReadIO(ring, ch1, dst, _))
      _ <- IO.raiseWhen(b1 == null)(new java.nio.channels.ClosedChannelException)
      n <- IO(b1.position())
      _ <- IO.raiseWhen(n <= 0)(new java.nio.channels.ClosedChannelException)
    } yield (n)
  }

  def read(timeOutMs: Int): IO[Chunk[Byte]] = {
    for {
      _ <- IO(this.timeOutMs = timeOutMs)
      bb <- IO(ByteBuffer.allocateDirect(TCPChannel.HTTP_READ_PACKET))
      b1 <- effectAsyncChannelIO(ring, ch1)((ring, ch1) => ioUringReadIO(ring, ch1, bb, _))
      _ <- IO
        .raiseError(new java.nio.channels.ClosedChannelException)
        .whenA(b1 == null || b1.position == 0)
    } yield (Chunk.byteBuffer(b1.flip))
  }

  /**
   * Writes data from a ByteBuffer to the channel, handling partial writes
   * @param buffer The ByteBuffer containing data to write
   * @return IO[ByteBuffer] containing the buffer after the write attempt, may have remaining data if write was partial
   */
  def writeBuf(buffer: ByteBuffer): IO[ByteBuffer] = {
    for {
      b1 <- effectAsyncChannelIO(ring, ch1)((ring, ch1) => ioUringWriteIO(ring, ch1, buffer, _))
        .handleErrorWith(e => IO.raiseError(e))
      _ <- IO
        .raiseError(new java.nio.channels.ClosedChannelException)
        .whenA(b1 == null || b1.position == 0)
    } yield (b1)
  }

  /**
   * Writes the complete contents of a ByteBuffer to the channel
   * Will continue writing until the entire buffer is written or an error occurs
   * @param buffer The ByteBuffer containing data to write
   * @return IO[Int] number of bytes written
   */
  def write(buffer: ByteBuffer): IO[Int] = {
    for {
     b <- writeBuf( buffer ).iterateUntil( b1 => b1.remaining() <= 0 )
    } yield( b.position() )
  }

  def close(): IO[Unit] = for {
    result <- IO.async[Unit](cb =>
      for {
        r <- IO(new Runnable {
          override def run() = {
            //close on socket fd, will be called in EVENT_HANDLER
            //then close will call onClose call back <- this is how we get here
            cb(Right(()))
          }
        })
        _ <- ring.queueClose(r, ch1)
      } yield (Some(IO.unit))
    )
  } yield (result)

  
  def closeSync(): IO[Unit] = for {
    _ <- IO.delay(ch1.close())
  } yield ()

  def secure() = false
  // used in TLS mode to pass parameter from SNI tls extension
  def remoteAddress(): IO[SocketAddress] = ???

}
