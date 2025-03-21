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
            if (buffer == null) {
              println("CB null - read")
            }
            cb(buffer)
          }
        })
      _ <- ring.queueRead(consumer, ch, bufferDirect)
    } yield ()
  }

  private def ioUringWriteIO(
      ring: IoUringEntry,
      ch: IoUringSocket,
      bufferDirect: ByteBuffer,
      cb: ByteBuffer => Unit
  ): IO[Unit] = {
    for {
      consumer <- IO(new Consumer[ByteBuffer] {
        override def accept(buffer: ByteBuffer): Unit = {
          if (buffer == null) {
            println("CB null - write")
          }
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

  /*
  private def submitAndGetForRead(ring: IoUring, timeOutMs: Long) : Int = {
    try {
      lock.lock()
      submit(ring)
      var ret = 0

      while {
        ret = ring.getCqes(timeOutMs)
        ret == 0
      } do ()

      ret

    } finally {
      lock.unlock()
    }
  }

  private def tryGetCqes(ring: IoUring, timeOutMs: Long) = {
    if (lock.tryLock()) {
      try {
        ring.getCqes(timeOutMs)
      } finally {
        lock.unlock()
      }
    }
  }*/

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

  def write(buffer: ByteBuffer): IO[Int] = {
    for {
      // _ <- IO(f_wRef.addAndGet(buffer.remaining()))
      b1 <- effectAsyncChannelIO(ring, ch1)((ring, ch1) => ioUringWriteIO(ring, ch1, buffer, _))
        .handleErrorWith(e => IO.println(">>>>" + e + "<<<<<") *> IO.raiseError(e))
      // Check for null buffer or zero bytes written which could indicate a stalled connection
      _ <- IO
        .raiseError(new java.nio.channels.ClosedChannelException)
        .whenA(b1 == null || b1.position == 0)
    } yield (b1.position())
  }

  def configureSocketTimeouts(): IO[Unit] = IO {
    // Set a timeout that's appropriate for your application needs
    // For Wi-Fi connections, shorter timeouts can help detect disconnections faster
    ch1.setTimeout(5000) // 5 seconds timeout
  }

  // configureSocketTimeouts()

  def close(): IO[Unit] = for {
    result <- IO.async[Unit](cb =>
      for {
        r <- IO(new Runnable {
          override def run() = {
            cb(Right(()))
          }
        })
        _ <- IO.println( "Initiated a close op")
        _ <- IO(ring.queueClose(r, ch1))
        _ <- IO.println( "close op is done")
      } yield (Some(IO.unit))
    )
  } yield (result)

  def close1(): IO[Unit] = for {
    _ <- IO.println("LOW CLOSE - Closing potentially stalled connection")
    _ <- IO.delay(ch1.close())
    // _ <- IO(ring.ring.close())
  } yield ()

  def secure() = false
  // used in TLS mode to pass parameter from SNI tls extension
  def remoteAddress(): IO[SocketAddress] = ???

}
