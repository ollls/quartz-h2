package io.quartz.util

import java.io.FileOutputStream
import cats.effect.{IO, Ref}
import cats.implicits.catsSyntaxApplicativeByName
import fs2.{Chunk, Pull, Stream}
import io.quartz.http2.model.{Headers, ContentType, Request}
import scala.util.control.Breaks._

import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import io.quartz.MyLogger._


object MultiPart {

  private def extractBoundaryFromMultipart(txt: String) = {
    val opt = txt.split(";")
    if (opt.length == 2) {
      val opt2 = opt(1).split("=")
      if (opt2.length == 2) "--" + opt2(1)
      else ""
    } else ""
  }

//Parse headers from chunk
  private def parseMPHeaders(h: Headers, mpblock: Chunk[Byte], boundary: String): (Boolean, Headers, Chunk[Byte]) = {
    var h_out = h
    val lines = new String(mpblock.toArray).split("\\R", 128) // .split("\\R+")
    var endOfHeaders = false
    var dataI = 0;
    breakable {
      for (i <- 0 to lines.length - 1) {
        val v = lines(i).split(":")
        dataI += lines(i).length + 2
        if (v.length == 1) { endOfHeaders = true; break }
        h_out = h_out + (v(0), v(1))
      }
    }
    (endOfHeaders, h_out, mpblock.drop(dataI)) // false when hedaers are expected in the next chunk
  }

  private def doMultiPart_scanAndDropBoundary(chunk: Chunk[Byte], boundary: String): Chunk[Byte] = {
    var bI = 0
    var cI = 0
    var bFound = false
    breakable {
      for (c <- chunk) {
        cI += 1
        if (bI >= boundary.length()) {
          bFound = true
          break
        }
        if (c == boundary(bI)) bI += 1 else bI = 0
      }
    }
    if (bFound) chunk.drop(cI + 1)
    else Chunk.empty[Byte]
  }

  private def doMultiPart_scanAndTakeBeforeBoundary(
      chunk: Chunk[Byte],
      boundary: String
  ): (Boolean, Chunk[Byte], Chunk[Byte]) = {
    var bI = 0
    var cI = 0
    var bFound = false
    breakable {
      for (c <- chunk) {
        cI += 1
        if (bI >= boundary.length()) {
          bFound = true
          break
        }
        if (c == boundary(bI)) bI += 1 else bI = 0
      }
    }
    if (bFound) (true, chunk.take(cI - boundary.length() - 3), chunk.drop(cI + 1))
    else (false, chunk, Chunk.empty[Byte]) // false - no term, whole chunk
  }

  private def go4_data(s: Stream[IO, Byte], boundary: String): Pull[IO, Headers | Chunk[Byte], Unit] = {
    s.pull.uncons.flatMap {
      case Some((hd1, tl)) =>
        val (stop, data_chunk, leftOver) = doMultiPart_scanAndTakeBeforeBoundary(hd1, boundary)
        if (stop == false) Pull.output(Chunk(data_chunk)) >> go4_data(tl, boundary)
        else if (data_chunk.isEmpty) go4(Headers(), tl.cons(leftOver), boundary, true)
        else Pull.output(Chunk(data_chunk)) >> go4(Headers(), tl.cons(leftOver), boundary, true)
      // check hd1 chunk for boundary, return true if boundary return false we do next chunk recursively
      case None => Pull.done
    }
  }

  private def go4(
      h: Headers,
      s: Stream[IO, Byte],
      boundary: String,
      hdrCont: Boolean = false
  ): Pull[IO, Headers | Chunk[Byte], Unit] = {
    s.pull.uncons.flatMap {
      case Some((hd1, tl)) =>
        val chunk = if (hdrCont) hd1 else doMultiPart_scanAndDropBoundary(hd1, boundary)
        val (done, hdr, leftOver) = parseMPHeaders(h, chunk, boundary)
        if (hdr.tbl.isEmpty) Pull.done
        else if (done) Pull.output(Chunk(hdr)) >> go4_data(tl.cons(leftOver), boundary)
        else go4(h, tl, boundary, hdrCont = true)
      case None => Pull.done
    }
  }

  private def stripQ(str: String) =
    str.stripPrefix("\"").stripSuffix("\"")

  /////////////////////////////////////////////////////////////////////////
  def stream(multiPartStream: Stream[IO, Byte], boundary: String): Stream[cats.effect.IO, Headers | Chunk[Byte]] =
    go4(Headers(), multiPartStream, boundary).stream

  ////////////////////////////////////////////////////////////////////////
  def stream(req: Request): IO[Stream[IO, Headers | Chunk[Byte]]] = for {
    contType <- IO(req.contentType.toString)
    _ <- IO
      .raiseError(new Exception("multipart/form-data content type is missing"))
      .whenA(contType.toLowerCase().startsWith("multipart/form-data") == false)
    boundary <- IO(extractBoundaryFromMultipart(contType))
  } yield (go4(Headers(), req.stream, boundary).stream)

  // write all multipart files from incoming request data stream
  def writeAll(req: Request, folderPath: String): IO[Unit] =
    for {

      contType <- IO(req.contentType.toString)
      _ <- IO
        .raiseError(new Exception("multipart/form-data content type is missing"))
        .whenA(contType.toLowerCase().startsWith("multipart/form-data") == false)

      boundary <- IO(extractBoundaryFromMultipart(contType))
      fileRef <- Ref.of[IO, FileOutputStream](null)

      s0 <- go4(Headers(), req.stream, boundary).stream
        .foreach {
          // each time we have headers we close and create FileOutputStream with file name from headers
          case h: Headers =>
            for {
              _ <- IO {
                Map.from(
                  h.get("Content-Disposition")
                    .get
                    .split(";")
                    .map(_.split("="))
                    .map(v1 => if (v1.length > 1) (v1(0).trim(), stripQ(v1(1).trim())) else (v1(0), ""))
                )("filename")
              }.flatTap(fname =>
                fileRef.get.map(fos => if (fos != null)(fos.close())) *> fileRef.set(
                  FileOutputStream(folderPath + fname) 
                )
              ).flatTap( fileName => Logger[IO].info( s"HTTP multipart request: processing file $fileName") )
            } yield ()
          case b: Chunk[Byte] =>
            for {
              fos <- fileRef.get
              _ <- IO.blocking(fos.write(b.toArray))
            } yield ()
        }
        .compile
        .drain
    } yield ()
}
