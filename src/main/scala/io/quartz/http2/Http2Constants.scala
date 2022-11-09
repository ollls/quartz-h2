 package io.quartz.http2

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets



sealed trait Priority {
  def isDefined: Boolean
}

object Priority {

  /** object representing the contents of a PRIORITY frame
    *
    * This is also used for the HEADERS frame which is logically a series of headers with a possible
    * PRIORITY frame
    */
  final case class Dependent(dependentStreamId: Int, exclusive: Boolean, priority: Int)
      extends Priority {
    require(0 <= dependentStreamId, "Invalid stream dependency")
    require(0 < priority && priority <= 256, "Weight must be 1 to 256")

    def isDefined: Boolean = true
  }

  /** Represents a lack of priority */
  case object NoPriority extends Priority {
    def isDefined: Boolean = false
  }
}

object Constants {
  val LengthFieldSize: Int = 3
  val HeaderSize: Int = 9

  val PrefaceString: String = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"

  def getPrefaceBuffer(): ByteBuffer =
    clientPrefaceBuffer.duplicate()

  object Masks {
    val INT31: Int = 0x7fffffff
    val INT32: Long = 0xffffffffL
    val EXCLUSIVE: Int = ~INT31
    val STREAMID: Int = INT31
    val LENGTH: Int = 0xffffff
  }

  object SettingsTypes {
    val HEADER_TABLE_SIZE: Short = 0x1
    val ENABLE_PUSH: Short = 0x2
    val MAX_CONCURRENT_STREAMS: Short = 0x3
    val INITIAL_WINDOW_SIZE: Short = 0x4
    val MAX_FRAME_SIZE: Short = 0x5
    val MAX_HEADER_LIST_SIZE: Short = 0x6
  }

  object FrameTypes {
    val DATA: Byte = 0x00
    val HEADERS: Byte = 0x01
    val PRIORITY: Byte = 0x02
    val RST_STREAM: Byte = 0x03
    val SETTINGS: Byte = 0x04
    val PUSH_PROMISE: Byte = 0x05
    val PING: Byte = 0x06
    val GOAWAY: Byte = 0x07
    val WINDOW_UPDATE: Byte = 0x08
    val CONTINUATION: Byte = 0x09
  }

  // ////////////////////////////////////////////////

  object Flags {
    val END_STREAM: Byte = 0x1
    def END_STREAM(flags: Byte): Boolean =
      checkFlag(flags, END_STREAM) // Data, Header

    val PADDED: Byte = 0x8
    def PADDED(flags: Byte): Boolean = checkFlag(flags, PADDED) // Data, Header

    val END_HEADERS: Byte = 0x4
    def END_HEADERS(flags: Byte): Boolean =
      checkFlag(flags, END_HEADERS) // Header, push_promise

    val PRIORITY: Byte = 0x20
    def PRIORITY(flags: Byte): Boolean = checkFlag(flags, PRIORITY) // Header

    val ACK: Byte = 0x1
    def ACK(flags: Byte): Boolean = checkFlag(flags, ACK) // ping

    def DepID(id: Int): Int = id & Masks.STREAMID
    def DepExclusive(id: Int): Boolean = (Masks.EXCLUSIVE & id) != 0

    @inline
    private[this] def checkFlag(flags: Byte, flag: Byte) = (flags & flag) != 0
  }

  case class ErrorGen(val streamId : Int, val code: Long, val name: String) extends Exception( name )
  case class ErrorRst(val streamId : Int, val code: Long, val name: String) extends Exception( name )

  object Error {
    val NO_ERROR = 0x0
    val PROTOCOL_ERROR = 0x1
    val INTERNAL_ERROR = 0x2
    val FLOW_CONTROL_ERROR = 0x3
    val SETTINGS_TIMEOUT = 0x4
    val STREAM_CLOSED = 0x5
    val FRAME_SIZE_ERROR = 0x6
    val REFUSED_STREAM = 0x7
    val CANCEL = 0x8
    val COMPRESSION_ERROR = 0x9
    val CONNECT_ERROR = 0xa
    val ENHANCE_YOUR_CALM = 0xb
    val INADEQUATE_SECURITY = 0xc
    val HTTP_1_1_REQUIRED = 0xd
  }

  private[this] val clientPrefaceBuffer: ByteBuffer =
    ByteBuffer
      .wrap(PrefaceString.getBytes(StandardCharsets.UTF_8))
      .asReadOnlyBuffer()
}
