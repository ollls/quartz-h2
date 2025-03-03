package io.quartz.netio
import java.net._

import java.nio.ByteBuffer
import cats.effect.IO
import fs2.Chunk

object SocketChannel {

  val HTTP_READ_PACKET = 16384

  def accept(ch: ServerSocket) =
    IO.blocking(new SocketChannel(ch.accept()))
}

class SocketChannel(val socket: Socket) extends IOChannel {

  def read(timeOut: Int): IO[Chunk[Byte]] =
    for {
      _ <- IO(socket.setSoTimeout(timeOut))
      buffer <- IO(Array.ofDim[Byte](SocketChannel.HTTP_READ_PACKET))
      nb <- IO.blocking(socket.getInputStream().read(buffer))
    } yield (Chunk.array(buffer, 0, nb))

  def write(buffer: ByteBuffer): IO[Int] =
    for {
      size <- IO(buffer.remaining())
      array <- IO(Array.ofDim[Byte](size))
      _ <- IO(buffer.get(array))
      _ <- IO.blocking(socket.getOutputStream().write(array))
    } yield (size)

  def close(): IO[Unit] = {
    IO {
      socket.close();
    }

  }
  def secure() = true // true only for this implementation

  def remoteAddress() = IO(socket.getRemoteSocketAddress())

  /* unimplemented */
  def readBuffer( dst: ByteBuffer,timeOut: Int): IO[Int] = ???
  def put(bb: ByteBuffer): IO[Unit] = ???

}
