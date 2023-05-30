package io.quartz.netio

import java.nio.channels.{
  AsynchronousChannelGroup,
  AsynchronousServerSocketChannel,
  AsynchronousSocketChannel,
  CompletionHandler
}

import java.util.concurrent.TimeUnit
import java.nio.channels.Channel
import cats.effect.IO
import java.nio.ByteBuffer
import fs2.Chunk
import java.net.StandardSocketOptions
import java.net.InetSocketAddress
import java.util.concurrent.Executors

object TCPChannel {

  val HTTP_READ_PACKET = 16384

  /////////////////////////////////
  def effectAsyncChannel[C <: Channel, A](
      ch: C
  )(op: C => CompletionHandler[A, Any] => Any): IO[A] = {
    IO.async[A](cb =>
      IO(op(ch)).flatMap(handler => {
        IO(handler(new CompletionHandler[A, Any] {
          def completed(result: A, u: Any): Unit = { cb(Right(result)) }
          def failed(t: Throwable, u: Any): Unit = {
            /*println("ef ****** " + t.toString());*/
            cb(Left(t))
          }
        })).map(_ => Some(IO.unit))
      })
    )
  }

  def accept(
      sch: AsynchronousServerSocketChannel
  ): IO[TCPChannel] =
    effectAsyncChannel[
      AsynchronousServerSocketChannel,
      AsynchronousSocketChannel
    ](sch)(c => c.accept((), _)).map( new TCPChannel(_))

  def connect(
      host: String,
      port: Int,
      group: AsynchronousChannelGroup = null
  ): IO[TCPChannel] = {
    val T = for {
      address <- IO(new InetSocketAddress(host, port))
      ch <- if (group == null) IO(AsynchronousSocketChannel.open()) else IO(AsynchronousSocketChannel.open(group))
      _ <- effectAsyncChannel[AsynchronousSocketChannel, Void](ch)(ch => ch.connect(address, (), _))
    } yield (ch)
    T.map(c => new TCPChannel(c))
  }

  def bind(addr: InetSocketAddress, socketGroupThreadsNum: Int): IO[AsynchronousServerSocketChannel] =
    for {
      group <- IO(AsynchronousChannelGroup.withThreadPool(Executors.newFixedThreadPool(socketGroupThreadsNum)))
      channel <- IO(group.provider().openAsynchronousServerSocketChannel(group))
      bind <- IO(channel.bind(addr))
    } yield (bind)

}

class TCPChannel( val ch: AsynchronousSocketChannel) extends IOChannel {
  // testing with big picture BLOBS
  //ch.setOption(StandardSocketOptions.SO_RCVBUF, 6 * 1024 * 1024);
  //ch.setOption(StandardSocketOptions.SO_SNDBUF, 6 * 1024 * 1024);
  //ch.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
  var f_putBack: ByteBuffer = null

  private[netio] def put(bb: ByteBuffer): IO[Unit] = IO { f_putBack = bb }

  private[netio] def readBuffer(
      dst: ByteBuffer,
      timeOut: Int
  ): IO[Int] = {
    for {
      _ <-
        if (f_putBack != null) {
          IO(dst.put(f_putBack)) >> IO { f_putBack = null }
        } else IO.unit

      n <- TCPChannel.effectAsyncChannel[AsynchronousSocketChannel, Integer](ch)(c =>
        c.read(dst, timeOut, TimeUnit.MILLISECONDS, (), _)
      )

      _ <- IO.raiseWhen(n.intValue() < 0)(new java.nio.channels.ClosedChannelException)

    } yield (n.intValue())
  }

  def rcvBufSize(nBytes: Int) = {
    ch.setOption[Integer](StandardSocketOptions.SO_RCVBUF, nBytes)
  }

  def sndBufSize(nBytes: Int) = {
    ch.setOption[Integer](StandardSocketOptions.SO_SNDBUF, nBytes)
  }

  def setOption[T](opt: java.net.SocketOption[T], val0: T) =
    ch.setOption(opt, val0)

  def read(
      timeOut: Int
  ): IO[Chunk[Byte]] = {
    for {
      bb <- IO(ByteBuffer.allocate(TCPChannel.HTTP_READ_PACKET))

      n <- TCPChannel.effectAsyncChannel[AsynchronousSocketChannel, Integer](ch)(c =>
        c.read(bb, timeOut, TimeUnit.MILLISECONDS, (), _)
      )

      _ <- IO.raiseWhen(n < 0)(new java.nio.channels.ClosedChannelException)

      chunk <- IO(Chunk.byteBuffer(bb.flip))

    } yield (chunk)
  }

  def write(chunk: Chunk[Byte]): IO[Int] = {
    val bb = chunk.toByteBuffer
    write(bb)
  }

  def write(buffer: ByteBuffer): IO[Int] = {
    TCPChannel
      .effectAsyncChannel[AsynchronousSocketChannel, Integer](ch)(c => ch.write(buffer, (), _))
      .map(_.intValue)
      .iterateWhile(_ => buffer.remaining() > 0)
  }

  def close(): IO[Unit] = IO(ch.close)

   def secure() = false

}
