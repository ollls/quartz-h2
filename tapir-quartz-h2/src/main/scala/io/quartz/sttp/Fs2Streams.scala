package io.quartz.sttp.capabilities.fs2

import cats.MonadThrow
import cats.effect.IO
import fs2.Pull
import fs2.Stream
import sttp.capabilities.StreamMaxLengthExceededException
import sttp.capabilities.Streams


trait Fs2IOStreams extends Streams[Fs2IOStreams] {
  override type BinaryStream = Stream[IO, Byte]
  override type Pipe[A, B] = fs2.Pipe[IO, A, B]
}

object Fs2IOStreams {
  def apply(): Fs2IOStreams = new Fs2IOStreams {}

  def limitBytes(stream: Stream[IO, Byte], maxBytes: Long): Stream[IO, Byte] = {
    def go(s: Stream[IO, Byte], remaining: Long): Pull[IO, Byte, Unit] = {
      if (remaining < 0) Pull.raiseError[IO](new StreamMaxLengthExceededException(maxBytes))
      else
        s.pull.uncons.flatMap {
          case Some((chunk, tail)) =>
            val chunkSize = chunk.size.toLong
            if (chunkSize <= remaining)
              Pull.output(chunk) >> go(tail, remaining - chunkSize)
            else
              Pull.raiseError[IO](new StreamMaxLengthExceededException(maxBytes))
          case None => Pull.done
        }
    }
    go(stream, maxBytes).stream
  }
}