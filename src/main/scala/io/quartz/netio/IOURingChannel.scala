package io.quartz.netio

import java.util.concurrent.locks.ReentrantLock
import java.net.SocketAddress
import java.util.function.{Consumer, BiConsumer}
import java.nio.ByteBuffer
import cats.effect.IO
import fs2.Chunk
import sh.blake.niouring.{IoUringServerSocket, IoUringSocket, IoUring}
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

class IOURingChannel(val ring: IoUring, val ch1: IoUringSocket) extends IOChannel {

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

  def effectAsyncChannel[A](ring: IoUring, ch: IoUringSocket)(
      op: (ring: IoUring, ch: IoUringSocket) => (A => Unit) => Any
  ) = {
    IO.async[A](cb =>
      IO(op(ring, ch)).flatMap(handler => {
        val f1: A => Unit = bb => { cb(Right(bb)) }
        // todo: investigate how to catch error
        handler(f1)
        // ^this will call queueRead/Write/etc.
        IO(Some(IO.unit))
      })
    )
  }

  private def ioUringRead(
      ring: IoUring,
      ch: IoUringSocket,
      bufferDirect: ByteBuffer,
      cb: ByteBuffer => Unit
  ): Unit = {

    val consumer =
      new Consumer[ByteBuffer] {
        override def accept(buffer: ByteBuffer): Unit = {
          cb(buffer)
        }
      }
    // associate a completion handler with a channel, just a variable assignment.
    ch.onRead(consumer)
    // queue up asyncronous read operation
    ring.queueRead(ch, bufferDirect)
    submit(ring)
    while( getCqes(ring) != true ) {}
  }

  private def ioUringWrite(
      ring: IoUring,
      ch: IoUringSocket,
      bufferDirect: ByteBuffer,
      cb: ByteBuffer => Unit
  ): Unit = {

    val consumer =
      new Consumer[ByteBuffer] {
        override def accept(buffer: ByteBuffer): Unit = {
          cb(buffer)
        }
      }
    // associate a completion handler with a channel, just a variable assignment.
    ch.onWrite(consumer)
    // queue up asyncronous read operation
    ring.queueWrite(ch, toDirectBuffer(bufferDirect))
    submit(ring)
  }

  private def submit( ring : IoUring) = {
    this.synchronized {
      ring.submit()
    }
  }

  private def getCqes(ring: IoUring) : Boolean = {
      try {
        lock.lock()
        ring.getCqes()
      } finally {
        lock.unlock()
      }
    }

  def read(timeOut: Int): IO[Chunk[Byte]] = {
    for {
      bb <- IO(ByteBuffer.allocateDirect(TCPChannel.HTTP_READ_PACKET))
      b1 <- effectAsyncChannel[ByteBuffer](ring, ch1)((ring, ch1) => ioUringRead(ring, ch1, bb, _))
      _ <- IO.raiseError(new Exception("read request aborted")).whenA(b1.position == 0)
    } yield (Chunk.byteBuffer(b1.flip))
  }

  def write(buffer: ByteBuffer): IO[Int] = {
    for {
      b1 <- effectAsyncChannel[ByteBuffer](ring, ch1)((ring, ch1) => ioUringWrite(ring, ch1, buffer, _))
    } yield (b1.position())
  }

  def close(): IO[Unit] = IO.delay(ch1.close())
  def secure() = false
  // used in TLS mode to pass parameter from SNI tls extension
  def remoteAddress(): IO[SocketAddress] = ???

}
