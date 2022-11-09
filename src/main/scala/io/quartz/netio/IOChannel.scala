package io.quartz.netio

import java.nio.ByteBuffer
import cats.effect.IO
import fs2.Chunk

trait IOChannel {
  def read( timeOut: Int): IO[Chunk[Byte]]
  def write(buffer: ByteBuffer): IO[Int]
  def close() : IO[Unit]
}
