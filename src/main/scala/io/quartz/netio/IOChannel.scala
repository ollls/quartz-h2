package io.quartz.netio

import java.nio.ByteBuffer
import cats.effect.IO
import fs2.Chunk
import java.net.SocketAddress

trait IOChannel {
  def read( timeOut: Int): IO[Chunk[Byte]]
  def write(buffer: ByteBuffer): IO[Int]
  def close() : IO[Unit]
  def secure() : Boolean
  //used in TLS mode to pass parameter from SNI tls extension
  def sniServerNames() : Option[Array[String]] = None
  def remoteAddress() : IO[SocketAddress]
  def readBuffer( dst: ByteBuffer,timeOut: Int): IO[Int]
  def put(bb: ByteBuffer): IO[Unit]
}
