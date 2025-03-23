package io.quartz.util

import cats.effect.IO
import java.io.FileInputStream
import fs2.Stream
import io.quartz.http2.model.{Headers, Method, StatusCode, ContentType, Response, Request}

object HttpRangeRequest {
  def makeResponse(req: Request, file: java.io.File, rangedType: ContentType, BLOCK_SIZE: Int = 32000): Response = {
    val Hdr_Range: Option[Array[String]] =
      req.headers.get("range").map(range => (range.split("=")(1))).map(_.split("-"))
    val jstream = new java.io.FileInputStream(file)

    Hdr_Range match {
      case None =>
        val fileContentType = ContentType.contentTypeFromFileName(file.getName)
        if (fileContentType != rangedType)
          Response
            .Ok()
            .asStream(fs2.io.readInputStream[IO](IO(jstream), BLOCK_SIZE, true))
            .contentType(ContentType.contentTypeFromFileName(file.getName))
        else
          Response
            .Ok()
            .hdr("Accept-Ranges", "bytes")
            .contentType(ContentType.contentTypeFromFileName(file.getName))

      case Some(minmax: Array[String]) =>
        val minmax =
          if (Hdr_Range.get.length > 1) Hdr_Range.map(m => (m(0).toLong, m(1).toLong)).get
          else Hdr_Range.map(m => (m(0).toLong, file.length() - 1)).get
        jstream.getChannel().position(minmax._1.toLong)
        Response
          .Error(StatusCode.PartialContent)
          .asStream(fs2.io.readInputStream[IO](IO(jstream), BLOCK_SIZE, true).take(minmax._2))
          .hdr("Content-Range", s"bytes ${minmax._1}-${minmax._2}/${file.length()}")
          .hdr("ETag", "3a64df551425-1603986224")
          .contentType(ContentType.contentTypeFromFileName(file.getName))
    }
  }
}
