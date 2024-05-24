package io.quartz.util
import fs2.{Stream, Pull, Chunk}
import cats.effect.IO


sealed case class ChunkedEncodingError(msg: String) extends Exception(msg)

object Chunked11 {

  /////////////////////////////////////////////////////////////////
  def chunk_drop_with_validation(data: Chunk[Byte], offset: Int) = {
    val data2 = data.drop(offset)
    // validate end of chunk
    val test = (data2(0) == '\r' && data2(1) == '\n')

    if (test == false) throw (new ChunkedEncodingError(""))

    data2.drop(2) // get rid of end of block markings

  }

  ////////////////////////////////////////////////////////////////
  private def extractChunkLen2(db: Chunk[Byte]): (Int, Int) = {
    var l = List.empty[Char]
    var c: Byte = 0
    var i: Int = 0
    while {
      { c = db(i); i += 1 }
      c != '\r' && i < 8 // 8 chars for chunked len
    } do (l = l.appended(c.toChar))
    if (c == '\r' && db(i) == '\n') i += 1
    else throw (new ChunkedEncodingError(""))
    (Integer.parseInt(l.mkString, 16), i)
  }

  ///////////////////////////////////////////////////////////////
  def makeChunkedStream(s1: Stream[IO, Byte], timeOutMs: Int) = {

    def go2(s: Stream[IO, Byte], hd: Chunk[Byte]): Pull[IO, Byte, Unit] = {
      val (chunkSize, offset) =
        try { extractChunkLen2(hd) } //special case when hd ends exactly on the http/11 chunk header, happens with multipart
        catch { case e: java.lang.IndexOutOfBoundsException => { (hd.size, 0) } }
      val len = hd.size - offset // actual data avaialble
      // println("INCOMING2>>>: " + chunkSize.toString() + "  " + offset.toString() + "  " + len)
      if (chunkSize == 0) Pull.done
      else if (len == chunkSize + 2)
        Pull.output[IO, Byte](hd.drop(offset).take(chunkSize)) >> go(
          s,
          chunk_drop_with_validation(hd, offset + chunkSize)
        )
      else if (len >= chunkSize + 2) // account for chunk end marking
        Pull.output[IO, Byte](hd.drop(offset).take(chunkSize)) >> go2(
          s,
          chunk_drop_with_validation(hd, offset + chunkSize)
        )
      else go(s, hd)
    }

    def go(s: Stream[IO, Byte], leftover: Chunk[Byte]): Pull[IO, Byte, Unit] = {
      s.pull.uncons.flatMap {
        case Some((hd1, tl)) =>
          val hd = leftover ++ hd1
          val (chunkSize, offset) =
            try { extractChunkLen2(hd) } //special case when hd ends exactly on the http/11 chunk header, happens with multipart
            catch { case e: java.lang.IndexOutOfBoundsException => { (hd.size, 0) } }
          val len = hd.size - offset // actual data avaialble
          // println("INCOMING>>>: " + chunkSize.toString() + "  " + offset.toString() + "  " + len)
          if (chunkSize == 0) Pull.done
          else if (len == chunkSize + 2)
            Pull.output[IO, Byte](hd.drop(offset).take(chunkSize)) >> go(
              tl,
              chunk_drop_with_validation(hd, offset + chunkSize)
            )
          else if (len > chunkSize + 2) // account for chunk end marking
            Pull.output[IO, Byte](hd.drop(offset).take(chunkSize)) >> go2(
              tl,
              chunk_drop_with_validation(hd, offset + chunkSize)
            )
          else go(tl, hd)
        case None => Pull.done
      }
    }
    go(s1, Chunk.empty[Byte]).stream.chunks

  }
}
