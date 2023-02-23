 package io.quartz.http2

import io.quartz.http2.Constants._
import java.nio.ByteBuffer
import cats.syntax.contravariantSemigroupal

object Http2Settings {

  def copy( out: Http2Settings, in: Http2Settings): Http2Settings = {
    out.ENABLE_PUSH = in.ENABLE_PUSH
    out.HEADER_TABLE_SIZE = in.HEADER_TABLE_SIZE
    out.INITIAL_WINDOW_SIZE = in.INITIAL_WINDOW_SIZE
    out.MAX_CONCURRENT_STREAMS = in.MAX_CONCURRENT_STREAMS
    out.MAX_FRAME_SIZE = in.MAX_FRAME_SIZE
    out.MAX_HEADER_LIST_SIZE = in.MAX_HEADER_LIST_SIZE
    out
  }

  def fromSettingsArray(b: ByteBuffer, len: Int) = {
    val settings = new Http2Settings()
    var cntr = 0;
    while (b.hasRemaining() && cntr < len) {
      val key: Short = b.getShort()
      val v: Int = b.getInt
      settings.setByID(key, v)
      cntr += 6
    }
    settings
  }
}

class Http2Settings {

  /** Allows the sender to inform the remote endpoint of the maximum size of the header compression table used to decode
    * header blocks, in octets. The encoder can select any size equal to or less than this value by using signaling
    * specific to the header compression format inside a header block.
    */
  var HEADER_TABLE_SIZE: Int = 4096
  //var HEADER_TABLE_SIZE: Int = 65536

  /** This setting can be used to disable server push (Section 8.2). */
  var ENABLE_PUSH: Int = 0

  /** Indicates the maximum number of concurrent streams that the sender will allow. This limit is directional: it
    * applies to the number of streams that the sender permits the receiver to create. Initially, there is no limit to
    * this value. It is recommended that this value be no smaller than 100, so as to not unnecessarily limit
    * parallelism.
    *
    * A value of 0 for SETTINGS_MAX_CONCURRENT_STREAMS SHOULD NOT be treated as special by endpoints. A zero value does
    * prevent the creation of new streams; however, this can also happen for any limit that is exhausted with active
    * streams. Servers SHOULD only set a zero value for short durations; if a server does not wish to accept requests,
    * closing the connection is more appropriate.
    */
  var MAX_CONCURRENT_STREAMS: Int = Integer.MAX_VALUE

  /** Indicates the sender's initial window size (in octets) for stream-level flow control.
    */
  var INITIAL_WINDOW_SIZE: Int = 65535

  /** Indicates the size of the largest frame payload that the sender is willing to receive, in octets.
    */
  var MAX_FRAME_SIZE: Int = 16384

  /** This advisory setting informs a peer of the maximum size of header list that the sender is prepared to accept, in
    * octets. The value is based on the uncompressed size of header fields, including the length of the name and value
    * in octets plus an overhead of 32 octets for each header field.
    */
  var MAX_HEADER_LIST_SIZE: Int = Integer.MAX_VALUE

  override def toString() = {

    val r = new StringBuilder
    r ++= s"HEADER_TABLE_SIZE = $HEADER_TABLE_SIZE\n"
    r ++= s"ENABLE_PUSH = $ENABLE_PUSH\n"
    r ++= s"MAX_CONCURRENT_STREAMS = $MAX_CONCURRENT_STREAMS\n"
    r ++= s"INITIAL_WINDOW_SIZE = $INITIAL_WINDOW_SIZE\n"
    r ++= s"MAX_FRAME_SIZE = $MAX_FRAME_SIZE\n"
    r ++= s"MAX_HEADER_LIST_SIZE = $MAX_HEADER_LIST_SIZE\n"

    r.toString()

  }

  /////////////////////////////////////////
  def toByteBuffer(b: ByteBuffer) = {

    b.putShort(SettingsTypes.HEADER_TABLE_SIZE)
    b.putInt(HEADER_TABLE_SIZE)

    b.putShort(SettingsTypes.ENABLE_PUSH)
    b.putInt(ENABLE_PUSH)

    b.putShort(SettingsTypes.MAX_CONCURRENT_STREAMS)
    b.putInt(MAX_CONCURRENT_STREAMS)

    b.putShort(SettingsTypes.INITIAL_WINDOW_SIZE)
    b.putInt(INITIAL_WINDOW_SIZE)

    b.putShort(SettingsTypes.MAX_FRAME_SIZE)
    b.putInt(MAX_FRAME_SIZE)

    b.putShort(SettingsTypes.MAX_HEADER_LIST_SIZE)
    b.putInt(MAX_HEADER_LIST_SIZE)
  }


  /////////////////////////////////////////
  def setByID(id: Short, value: Int) = {
    id match {
      case SettingsTypes.HEADER_TABLE_SIZE => HEADER_TABLE_SIZE = value
      case SettingsTypes.ENABLE_PUSH       => ENABLE_PUSH = value
      case SettingsTypes.MAX_CONCURRENT_STREAMS =>
        MAX_CONCURRENT_STREAMS = value
      case SettingsTypes.INITIAL_WINDOW_SIZE  => INITIAL_WINDOW_SIZE = value
      case SettingsTypes.MAX_FRAME_SIZE       => MAX_FRAME_SIZE = value
      case SettingsTypes.MAX_HEADER_LIST_SIZE => MAX_HEADER_LIST_SIZE = value
    }

  }

}
