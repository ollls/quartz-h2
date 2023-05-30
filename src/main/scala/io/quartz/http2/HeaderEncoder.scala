/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.quartz.http2

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.US_ASCII

import com.twitter.hpack.Encoder
import com.twitter.hpack.{Decoder, HeaderListener}
import io.quartz.util.ByteBufferInputStream
import io.quartz.http2.model.Headers

/** HTTP/2 HPACK header encoder
  *
  * @param initialMaxTableSize
  *   maximum HPACK table size the peer will allow.
  */
class HeaderEncoder(initialMaxTableSize: Int) {
  private[this] val encoder = new Encoder(initialMaxTableSize)
  private[this] val os = new ByteArrayOutputStream(4096) // ???????

  /** This should only be changed by the peer */
  def maxTableSize(max: Int): Unit =
    encoder.setMaxHeaderTableSize(os, max)

  /** Encode the headers into the payload of a HEADERS frame */
  def encodeHeaders(hs: Headers): ByteBuffer = { // hSem used

    hs.foreach { case (k, v) =>
      val keyBytes = k.toLowerCase().getBytes(US_ASCII)
      val valueBytes = v.getBytes(US_ASCII)
      if (keyBytes(0) == ':')
        encoder.encodeHeader(os, keyBytes, valueBytes, false)
    }
    hs.foreach { case (k, v) =>
      val keyBytes = k.toLowerCase().getBytes(US_ASCII)
      val valueBytes = v.getBytes(US_ASCII)
      if (keyBytes(0) != ':')
        encoder.encodeHeader(os, keyBytes, valueBytes, false)
    }
    val buff = ByteBuffer.wrap(os.toByteArray())
    os.reset()
    buff
  }
}

/** HTTP/2 HPACK header decoder
  * @param maxHeaderListSize
  * @param maxTableSize
  */

class HeaderDecoder(maxHeaderListSize: Int, val maxTableSize: Int) {

  private[this] val decoder = new Decoder(maxHeaderListSize, maxTableSize)

  private[this] var headerBlockSize = 0 // ???

  private[this] var headers: Headers = null

  private[this] var regularHeaderWasAdded = false

  private[this] val listener = new HeaderListener {
    override def addHeader(
        name: Array[Byte],
        value: Array[Byte],
        sensitive: Boolean
    ): Unit = {

      val name_s = new String(name, US_ASCII)
      val value_s = new String(value, US_ASCII)

      if (name_s.startsWith(":") == false) regularHeaderWasAdded = true
      else if (regularHeaderWasAdded == true)
        throw new Exception(
          "An attempt to add pseudo header after a regular header"
        )

      // System.out.println(new String(name, US_ASCII) + " ------> " + new String(value, US_ASCII))

      headerBlockSize += 32 + name.length + value.length // implement size check!!!!
      headers = headers + (name_s -> value_s)

      if (headerBlockSize > maxHeaderListSize)
        throw new Exception(
          "HTTP2 settings value MAX_HEADER_LIST_SIZE exceeded limit"
        )

    }
  }

  private[this] def concatBB(prepend: ByteBuffer, to: ByteBuffer) = {
    if (prepend.hasRemaining() == true) {
      val pre_bb = prepend.remaining()
      val after_bb = to.remaining
      val resultBB = ByteBuffer.allocate(pre_bb + after_bb)
      resultBB.put(prepend)
      resultBB.put(to)
      resultBB.rewind()
      resultBB
    } else { ByteBuffer.allocate(to.remaining).put(to).rewind }
  }

  def decodeHeaders(buffer_array: Seq[ByteBuffer]): Headers = {
    regularHeaderWasAdded = false
    headers = Headers()
    val leftOver = ByteBuffer.allocate(0)
    doDecodeHeaders(buffer_array, leftOver)
    headers
  }

  // simplified version ByteBuffer always has concatanated headers from headers and cont packets.
  private[this] def doDecodeHeaders(
      buffer: Seq[ByteBuffer],
      leftOver: ByteBuffer
  ): Unit = {

    buffer match {
      case head :: next => {
        val buf = this.concatBB(leftOver, head)
        // try {
        decoder.decode(new ByteBufferInputStream(buf), listener)
        // } catch {
        //    case e: Exception => println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!" + e.toString + e.getMessage)
        // }
        doDecodeHeaders(next, buf)
      }
      case Nil => { decoder.endHeaderBlock() }

    }
  }

}
