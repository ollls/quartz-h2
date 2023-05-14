package io.quartz.util

import cats.effect.IO
import fs2.Chunk
import cats.implicits._

import io.quartz.netio.IOChannel
import io.quartz.http2.model.Headers
import java.io._

object Utils {

  val header_pair = raw"(.{2,100}):\s+(.+)".r
  val http_line = raw"([A-Z]{3,8})\s+(.+)\s+(HTTP/.+)".r
  val HTTP11_HEADER_SIZE_LIMIT = 524288

  def parseHeaderLine(line: String, hdrs: Headers, secure: Boolean): Headers =
    line match {
      case http_line(method, path, _) =>
        hdrs ++ Headers(
          ":method" -> method,
          ":path" -> path,
          ":scheme" -> (if (secure) "https" else "http")
        ) // FIX TBD - no schema for now, ":scheme" -> prot)
      case header_pair(attr, value) => hdrs + (attr.toLowerCase -> value)
      case _                        => hdrs
    }

  def getHttpHeaderAndLeftover(chunk: Chunk[Byte], secure: Boolean): IO[(Headers, Chunk[Byte])] =
    IO {
      var cur = chunk
      var stop = false
      var complete = false
      var hdrs = Headers()

      while (stop == false) {
        val i = cur.indexWhere(_ == 0x0d).get
        if (i < 0) {
          stop = true
        } else {
          val line = cur.take(i)
          hdrs = parseHeaderLine(new String(line.toArray), hdrs, secure)
          cur = cur.drop(i + 2)
          if (line.size == 0) {
            complete = true;
            stop = true;
          }
        }
      }
      // won't use stream to fetch all headers, must be present at once in one bufer read ops.
      if (complete == false)
        IO.raiseError(new Exception("chunk buffer doesn't have complete header data"))
      (hdrs, cur)
    }

  private def testWithStatePos(byte: Byte, pattern: Array[Byte], statusPos: Int): Int = {
    if (byte == pattern(statusPos)) statusPos + 1
    else 0
  }

  private def chunkSearchFor2CR(chunk: Chunk[Byte]) = {
    val pattern = "\r\n\r\n".getBytes()

    var cntr = 0;
    var stop = false;
    var statusPos = 0; // state shows how many bytes matched in pattern

    while (!stop)
    {
      if (cntr < chunk.size) {
        val b = chunk(cntr)
        cntr += 1
        statusPos = testWithStatePos(b, pattern, statusPos)
        if (statusPos == pattern.length) stop = true

      } else { stop = true; cntr = 0 }
    }

    cntr

  }

  def splitHeadersAndBody(c: IOChannel, timeOutMs: Int, chunk: Chunk[Byte]): IO[(Chunk[Byte], Chunk[Byte])] = {
    val split = chunkSearchFor2CR(chunk)
    if (split > 0) IO(chunk.splitAt(split))
    else
      for {

        _ <- IO
          .raiseError(new Exception("io.quartz.util.Utils Header size exceeded limit"))
          .whenA(chunk.size > HTTP11_HEADER_SIZE_LIMIT)

        next_chunk <- c.read(timeOutMs)
        result <- splitHeadersAndBody(c, timeOutMs, chunk ++ next_chunk)
      } yield (result)
  }
}
