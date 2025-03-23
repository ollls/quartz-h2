package io.quartz.util
import fs2.{Stream, Pull, Chunk}
import cats.effect.IO

/**
 * HTTP/1.1 Chunked Transfer Encoding Handler
 * ----------------------------------------
 * Implements RFC 7230 compliant chunked transfer encoding processing.
 * Efficiently processes chunked HTTP data streams with proper validation.
 * 
 * Key features:
 * - Chunk size extraction and validation
 * - Proper handling of chunk terminators (CRLF)
 * - Stream-based processing for memory efficiency
 * - Error handling for malformed chunks
 */
sealed case class ChunkedEncodingError(msg: String) extends Exception(msg)

object Chunked11 {

  /**
   * Validates and drops chunk terminators
   * @param data The chunk data
   * @param offset Position to start validation from
   * @return Remaining data after removing chunk terminators
   * @throws ChunkedEncodingError if chunk terminator is invalid
   */
  def chunk_drop_with_validation(data: Chunk[Byte], offset: Int): Chunk[Byte] = {
    val data2 = data.drop(offset)
    // validate end of chunk (CRLF)
    if (data2.size < 2 || data2(0) != '\r' || data2(1) != '\n') {
      throw new ChunkedEncodingError("Invalid chunk terminator")
    }

    data2.drop(2) // get rid of end of block markings
  }

  /**
   * Extracts chunk length from the beginning of a chunk
   * @param db Chunk data containing the length header
   * @return Tuple of (chunk size, offset to chunk data)
   * @throws ChunkedEncodingError if chunk header is malformed
   */
  private def extractChunkLen2(db: Chunk[Byte]): (Int, Int) = {
    var l = List.empty[Char]
    var c: Byte = 0
    var i: Int = 0
    
    // Read up to 8 hex characters for the chunk length
    while (i < db.size && i < 8 && { c = db(i); i += 1; c != '\r' }) {
      l = l.appended(c.toChar)
    }
    
    // Validate chunk header terminator (CRLF)
    if (i >= db.size || c != '\r' || i >= db.size + 1 || db(i) != '\n') {
      throw new ChunkedEncodingError("Malformed chunk header")
    }
    
    i += 1 // Move past '\n'
    
    try {
      (Integer.parseInt(l.mkString, 16), i)
    } catch {
      case e: NumberFormatException => 
        throw new ChunkedEncodingError("Invalid chunk size format")
    }
  }

  /**
   * Creates a stream that processes HTTP/1.1 chunked encoded data
   * @param s1 Input stream of bytes
   * @param timeOutMs Timeout in milliseconds (currently unused)
   * @return Stream of decoded bytes with chunked encoding removed
   */
  def makeChunkedStream(s1: Stream[IO, Byte], timeOutMs: Int): Stream[IO, Chunk[Byte]] = {

    /**
     * Process a chunk when we already have enough data in the buffer
     */
    def go2(s: Stream[IO, Byte], hd: Chunk[Byte]): Pull[IO, Byte, Unit] = {
      try {
        val (chunkSize, offset) = extractChunkLen2(hd)
        val len = hd.size - offset // actual data available
        
        if (chunkSize == 0) {
          // Last chunk (size 0) - end of chunked data
          Pull.done
        } else if (len == chunkSize + 2) {
          // We have exactly one chunk plus its terminator
          Pull.output[IO, Byte](hd.drop(offset).take(chunkSize)) >> go(
            s,
            chunk_drop_with_validation(hd, offset + chunkSize)
          )
        } else if (len >= chunkSize + 2) {
          // We have at least one chunk plus its terminator and more data
          Pull.output[IO, Byte](hd.drop(offset).take(chunkSize)) >> go2(
            s,
            chunk_drop_with_validation(hd, offset + chunkSize)
          )
        } else {
          // Not enough data, need to read more
          go(s, hd)
        }
      } catch {
        case e: java.lang.IndexOutOfBoundsException => 
          // Not enough data to extract chunk length
          go(s, hd)
        case e: ChunkedEncodingError =>
          // Propagate chunked encoding errors
          Pull.raiseError[IO](e)
      }
    }

    /**
     * Main processing function that pulls data from the stream
     */
    def go(s: Stream[IO, Byte], leftover: Chunk[Byte]): Pull[IO, Byte, Unit] = {
      s.pull.uncons.flatMap {
        case Some((hd1, tl)) =>
          val hd = leftover ++ hd1
          try {
            val (chunkSize, offset) = extractChunkLen2(hd)
            val len = hd.size - offset // actual data available
            
            if (chunkSize == 0) {
              // Last chunk (size 0) - end of chunked data
              Pull.done
            } else if (len == chunkSize + 2) {
              // We have exactly one chunk plus its terminator
              Pull.output[IO, Byte](hd.drop(offset).take(chunkSize)) >> go(
                tl,
                chunk_drop_with_validation(hd, offset + chunkSize)
              )
            } else if (len > chunkSize + 2) {
              // We have at least one chunk plus its terminator and more data
              Pull.output[IO, Byte](hd.drop(offset).take(chunkSize)) >> go2(
                tl,
                chunk_drop_with_validation(hd, offset + chunkSize)
              )
            } else {
              // Not enough data, need to read more
              go(tl, hd)
            }
          } catch {
            case e: java.lang.IndexOutOfBoundsException => 
              // Not enough data to extract chunk length
              go(tl, hd)
            case e: ChunkedEncodingError =>
              // Propagate chunked encoding errors
              Pull.raiseError[IO](e)
          }
        case None => Pull.done
      }
    }
    
    go(s1, Chunk.empty[Byte]).stream.chunks
  }
}
