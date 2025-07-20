package io.quartz.http2

import io.quartz.http2.Constants._
import java.nio.ByteBuffer

object Frames {

  // No need to create new underlying arrays every time since at most it will be 255 bytes of 0's
  private[this] val sharedPadding: ByteBuffer =
    ByteBuffer.allocateDirect(255).asReadOnlyBuffer()

  private[this] def tailPadding(padBytes: Int): List[ByteBuffer] =
    if (0 < padBytes) {
      val b = sharedPadding.duplicate()
      b.limit(padBytes)
      b :: Nil
    } else Nil

  private[this] def writePriority(p: Priority.Dependent, buffer: ByteBuffer): Unit = {
    if (p.exclusive)
      buffer.putInt(p.dependentStreamId | Masks.EXCLUSIVE)
    else
      buffer.putInt(p.dependentStreamId)

    buffer.put(((p.priority - 1) & 0xff).toByte)
    ()
  }

  def checkPreface(b: ByteBuffer): Boolean = {
    var stop = false
    val p = Constants.getPrefaceBuffer()

    while (p.hasRemaining() && stop == false) {
      val rb = p.get()
      val cb = b.get()

      if (rb != cb) stop = true
    }
    !stop
  }

  private def writeFrameHeader(
      length: Int,
      frameType: Byte,
      flags: Byte,
      streamdId: Int,
      buffer: ByteBuffer
  ): Unit = {
    buffer
      .put((length >>> 16 & 0xff).toByte)
      .put((length >>> 8 & 0xff).toByte)
      .put((length & 0xff).toByte)
      .put(frameType)
      .put(flags)
      .putInt(streamdId & Masks.STREAMID)
    ()
  }

  def getLengthField(buffer: ByteBuffer): Int =
    ((buffer.get() & 0xff) << 16) |
      ((buffer.get() & 0xff) << 8) |
      ((buffer.get() & 0xff) << 0)

  def getStreamId(buffer: ByteBuffer): Int =
    buffer.getInt() & Masks.STREAMID

  def makeSettingsFrameClient(
      ack: Boolean = true,
      settings: Http2Settings
  ): ByteBuffer = {

    val payloadSize = 3 * 6
    val buffer = ByteBuffer.allocateDirect(HeaderSize + payloadSize)
    val flags = if (ack) Flags.ACK.toInt else 0x0

    writeFrameHeader(payloadSize, FrameTypes.SETTINGS, flags.toByte, 0, buffer)

    buffer.putShort(SettingsTypes.ENABLE_PUSH).putInt(settings.ENABLE_PUSH)

    buffer
      .putShort(SettingsTypes.INITIAL_WINDOW_SIZE)
      .putInt(settings.INITIAL_WINDOW_SIZE)

    buffer
      .putShort(SettingsTypes.MAX_CONCURRENT_STREAMS)
      .putInt(100)

    buffer.flip()
    buffer
  }

  def makeSettingsFrameE(ack: Boolean = true): ByteBuffer = {
    val payloadSize = 0
    val flags = if (ack) Flags.ACK.toInt else 0x0
    val buffer = ByteBuffer.allocateDirect(HeaderSize + payloadSize)
    writeFrameHeader(payloadSize, FrameTypes.SETTINGS, flags.toByte, 0, buffer)

    buffer.flip()

    buffer
  }

  def mkRstStreamFrame(streamId: Int, errorCode: Long): ByteBuffer = {
    require(0 < streamId, "Invalid RST_STREAM stream id")
    require(0 <= errorCode && errorCode <= Masks.INT32, "Invalid error code")

    val payloadSize = 4
    val buffer = ByteBuffer.allocateDirect(HeaderSize + payloadSize)
    writeFrameHeader(payloadSize, FrameTypes.RST_STREAM, 0x0, streamId, buffer)
    buffer.putInt((errorCode & Masks.INT32).toInt)
    buffer.flip()

    buffer
  }

  def mkGoAwayFrame(
      lastStreamId: Int,
      error: Long,
      debugData: Array[Byte]
  ): ByteBuffer = {
    require(0 <= lastStreamId, "Invalid last stream id for GOAWAY frame")
    val size = 8
    val buffer = ByteBuffer.allocateDirect(HeaderSize + size + debugData.length)
    writeFrameHeader(size + debugData.length, FrameTypes.GOAWAY, 0x0, 0, buffer)

    buffer
      .putInt(lastStreamId & Masks.INT31)
      .putInt(error.toInt)
      .put(debugData)
      .flip()
    buffer

  }

  def makeSettingsAckFrame(): ByteBuffer = {

    val payloadSize = 0
    val buffer = ByteBuffer.allocateDirect(HeaderSize + payloadSize)
    val flags = Flags.ACK.toInt

    writeFrameHeader(payloadSize, FrameTypes.SETTINGS, flags.toByte, 0, buffer)

    buffer.flip()
    buffer
  }

  def makeSettingsFrame(
      ack: Boolean = true,
      settings: Http2Settings
  ): ByteBuffer = {

    val payloadSize = 6 * 6
    val buffer = ByteBuffer.allocateDirect(HeaderSize + payloadSize)
    val flags = if (ack) Flags.ACK.toInt else 0x0

    writeFrameHeader(payloadSize, FrameTypes.SETTINGS, flags.toByte, 0, buffer)

    buffer
      .putShort(SettingsTypes.HEADER_TABLE_SIZE)
      .putInt(settings.HEADER_TABLE_SIZE)

    buffer.putShort(SettingsTypes.ENABLE_PUSH).putInt(settings.ENABLE_PUSH)

    buffer
      .putShort(SettingsTypes.MAX_CONCURRENT_STREAMS)
      .putInt(settings.MAX_CONCURRENT_STREAMS)

    buffer
      .putShort(SettingsTypes.INITIAL_WINDOW_SIZE)
      .putInt(settings.INITIAL_WINDOW_SIZE)

    buffer
      .putShort(SettingsTypes.MAX_FRAME_SIZE)
      .putInt(settings.MAX_FRAME_SIZE)

    buffer
      .putShort(SettingsTypes.MAX_HEADER_LIST_SIZE)
      .putInt(settings.MAX_HEADER_LIST_SIZE)

    buffer.flip()
    buffer
  }

  def mkPingFrame(ack: Boolean, data: Array[Byte]): ByteBuffer = {
    val PingSize = 8
    require(data.length == PingSize, "Ping data must be 8 bytes long")

    val flags: Byte = if (ack) Flags.ACK else 0x0

    val buffer = ByteBuffer.allocateDirect(HeaderSize + PingSize)
    writeFrameHeader(PingSize, FrameTypes.PING, flags, 0, buffer)
    buffer.put(data).flip()

    buffer
  }

  /** Create a DATA frame
    *
    * @param streamId
    *   stream id of the associated data frame
    * @param endStream
    *   whether to set the END_STREAM flag
    * @param padding
    *   number of octets by which to pad the message, with 0 meaning the flag is not set, 1 meaning the flag is set and
    *   the pad length field is added, and padding = [2-256] meaning the flag is set, length field is added, and
    *   (padding - 1) bytes (0x00) are added to the end of the frame.
    * @param data
    *   data consisting of the payload
    */
  def mkDataFrame(streamId: Int, endStream: Boolean, padding: Int, data: ByteBuffer): ByteBuffer = {
    require(0 < streamId, "bad DATA frame stream id")
    require(0 <= padding && padding <= 256, "Invalid padding of DATA frame")

    val padded = 0 < padding
    var flags = 0x0
    if (padded)
      flags |= Flags.PADDED

    if (endStream)
      flags |= Flags.END_STREAM

    val payloadSize = data.remaining + padding
    val dataFrame = ByteBuffer.allocateDirect(HeaderSize + (if (padded) 1 else 0) + payloadSize)

    writeFrameHeader(payloadSize, FrameTypes.DATA, flags.toByte, streamId, dataFrame)

    if (padded)
      // padding of 1 is represented by the padding field and no trailing padding
      dataFrame.put((padding - 1).toByte)

    dataFrame.put(data)
    dataFrame.flip()

    dataFrame
  }

  def mkWindowUpdateFrame(streamId: Int, increment: Int): ByteBuffer = {
    require(0 <= streamId, "Invalid stream id for WINDOW_UPDATE")
    require(
      0 < increment && increment <= Integer.MAX_VALUE,
      "Invalid stream increment for WINDOW_UPDATE"
    )

    val size = 4
    val buffer = ByteBuffer.allocateDirect(HeaderSize + size)
    writeFrameHeader(size, FrameTypes.WINDOW_UPDATE, 0x0, streamId, buffer)
    buffer
      .putInt(Masks.INT31 & increment)
      .flip()

    buffer
  }

  def mkContinuationFrame(streamId: Int, endHeaders: Boolean, headerBuffer: ByteBuffer): ByteBuffer = {
    require(0 < streamId, "Invalid stream id for CONTINUATION frame")
    val flags: Byte = if (endHeaders) Flags.END_HEADERS else 0x0

    val payloadSize = headerBuffer.remaining

    val buffer = ByteBuffer.allocateDirect(HeaderSize + payloadSize)

    writeFrameHeader(headerBuffer.remaining, FrameTypes.CONTINUATION, flags, streamId, buffer)
    buffer.put(headerBuffer)

    buffer.flip()

    buffer

  }

  def mkHeaderFrame(
      streamId: Int,
      priority: Priority,
      endHeaders: Boolean,
      endStream: Boolean,
      padding: Int,
      headerData: ByteBuffer
  ): ByteBuffer = {
    require(0 < streamId, "bad HEADER frame stream id")
    require(0 <= padding, "Invalid padding of HEADER frame")

    val padded = 0 < padding
    var flags = 0x0

    var nonDataSize = 0

    if (padded) {
      nonDataSize += 1 // padding byte
      flags |= Flags.PADDED
    }

    if (priority.isDefined) {
      nonDataSize += 4 + 1 // stream dep and weight
      flags |= Flags.PRIORITY
    }

    if (endHeaders)
      flags |= Flags.END_HEADERS

    if (endStream)
      flags |= Flags.END_STREAM

    val payloadSize = nonDataSize + headerData.remaining + padding

    val header = ByteBuffer.allocateDirect(HeaderSize + nonDataSize + payloadSize)

    writeFrameHeader(payloadSize, FrameTypes.HEADERS, flags.toByte, streamId, header)

    if (padded)
      // padding of 1 is represented by the padding field and no trailing padding
      header.put((padding - 1).toByte)

    priority match {
      case p: Priority.Dependent => writePriority(p, header)
      case Priority.NoPriority   => // NOOP
    }

    header.put(headerData)
    header.flip()

    header
  }

  /*
  def decodeSettingsPacketWithValidation(
      buffer: ByteBuffer,
      required: Http2Settings
  ): Either[String, Http2Settings] = {
    val len = getLengthField(buffer)
    val frameType = buffer.get()
    val flags = buffer.get()

    val streamId = getStreamId(buffer)

    val s = Http2Settings.fromSettingsArrayWithValidation(buffer, len, required)

    s
  }*/
  /*
  def decodeSettingsPacket(buffer: ByteBuffer): Http2Settings = {
    val len = getLengthField(buffer)
    val frameType = buffer.get()
    val flags = buffer.get()

    val streamId = getStreamId(buffer)

    val s = Http2Settings.fromSettingsArray(buffer, len)

    s
  }*/

}
