package io.quartz.netio

import java.util.concurrent.locks.ReentrantLock
import java.net.SocketAddress
import java.util.function.{Consumer, BiConsumer}
import java.nio.ByteBuffer
import cats.effect.IO
import fs2.Chunk
import io.quartz.iouring.{IoUringServerSocket, IoUringSocket, IoUring}
import scala.concurrent.ExecutionContextExecutorService
import cats.implicits._

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

  def accept(ring: IoUring, serverSocket: IoUringServerSocket): IO[(IoUring, IoUringSocket)] = {
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

class IOURingChannel(val ring: IoUringEntry, val ch1: IoUringSocket, var timeOutMs: Long) extends IOChannel {

  var f_putBack: ByteBuffer = null

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


  def effectAsyncChannelIO[A](ring: IoUringEntry, ch: IoUringSocket)(
      op: (ring: IoUringEntry, ch: IoUringSocket) => (A => Unit) => IO[Any]
  ) = {
    IO.async[A](cb =>
      IO(op(ring, ch)).flatMap(handler => {
        val f1: A => Unit = bb => { cb(Right(bb)) }
        // todo: investigate how to catch error
        handler(f1) *> IO(Some(IO.unit))
      })
    )
  }

  private def ioUringReadIO(
      ring:  IoUringEntry,
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
      _ <- ring.queueWrite(consumer, ch, /*toDirectBuffer(*/bufferDirect)
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
      b1 <- effectAsyncChannelIO[ByteBuffer](ring, ch1)((ring, ch1) => ioUringReadIO(ring, ch1, dst, _))
      n <- IO(b1.position())
      _ <- IO.raiseWhen(n <= 0)(new java.nio.channels.ClosedChannelException)

    } yield (n)
  }

  def read(timeOutMs: Int): IO[Chunk[Byte]] = {
    for {
      _ <- IO(this.timeOutMs = timeOutMs)
      bb <- IO(ByteBuffer.allocateDirect(TCPChannel.HTTP_READ_PACKET))
      b1 <- effectAsyncChannelIO[ByteBuffer](ring, ch1)((ring, ch1) => ioUringReadIO(ring, ch1, bb, _))
      _ <- IO.raiseError(new java.nio.channels.ClosedChannelException).whenA(b1.position == 0)
    } yield (Chunk.byteBuffer(b1.flip))
  }

  def write(buffer: ByteBuffer): IO[Int] = {
    for {
      b1 <- effectAsyncChannelIO[ByteBuffer](ring, ch1)((ring, ch1) => ioUringWriteIO(ring, ch1, buffer, _))
    } yield (b1.position())
  }

  def close(): IO[Unit] = IO.delay(ch1.close())
  def secure() = false
  // used in TLS mode to pass parameter from SNI tls extension
  def remoteAddress(): IO[SocketAddress] = ???

}
