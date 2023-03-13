package io.quartz.netio

import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.HandshakeStatus._
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.security.KeyStore

import javax.net.ssl.{SSLEngineResult, SSLSession}

import cats.effect.Ref

import java.util.concurrent.TimeUnit
import java.nio.channels.Channel
import cats.effect.{IO, IOApp, ExitCode}
import java.nio.ByteBuffer
import fs2.Chunk

import java.nio.channels.{
  AsynchronousChannelGroup,
  AsynchronousServerSocketChannel,
  AsynchronousSocketChannel,
  CompletionHandler
}

import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import java.io.FileInputStream
import java.io.File
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.KeyManagerFactory
import scala.jdk.CollectionConverters.ListHasAsScala
import java.nio.ByteBuffer

sealed case class TLSChannelError(msg: String) extends Exception(msg)

object TLSChannel {

  val READ_HANDSHAKE_TIMEOUT_MS = 5000
  val TLS_PROTOCOL_TAG = "TLSv1.2"

  private def loadDefaultKeyStore(): KeyStore = {
    val relativeCacertsPath = "/lib/security/cacerts".replace("/", File.separator);
    val filename = System.getProperty("java.home") + relativeCacertsPath;
    val is = new FileInputStream(filename);

    val keystore = KeyStore.getInstance(KeyStore.getDefaultType());
    val password = "changeit";
    keystore.load(is, password.toCharArray());

    keystore;
  }

  def buildSSLContext(protocol: String, JKSkeystore: String, password: String) = {
    // JKSkeystore == null, only if blind trust was requested

    val sslContext: SSLContext = SSLContext.getInstance(protocol)

    val keyStore = if (JKSkeystore == null) {
      loadDefaultKeyStore()
    } else {
      val keyStore: KeyStore = KeyStore.getInstance("JKS")
      val ks = new java.io.FileInputStream(JKSkeystore)
      keyStore.load(ks, password.toCharArray())
      keyStore
    }

    val trustMgrs = if (JKSkeystore == null) {
      Array[TrustManager](new X509TrustManager() {
        def getAcceptedIssuers(): Array[X509Certificate] = null
        def checkClientTrusted(c: Array[X509Certificate], a: String): Unit = ()
        def checkServerTrusted(c: Array[X509Certificate], a: String): Unit = ()
      })

    } else {
      val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
      tmf.init(keyStore)
      tmf.getTrustManagers()
    }

    val pwd = if (JKSkeystore == null) "changeit" else password

    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(keyStore, pwd.toCharArray())
    sslContext.init(kmf.getKeyManagers(), trustMgrs, null);

    sslContext
  }

  def connect(
      host: String,
      port: Int,
      group: AsynchronousChannelGroup = null,
      blindTrust: Boolean = false,
      trustKeystore: String = null,
      password: String = ""
  ): IO[TLSChannel] = {
    val T = for {
      ssl_ctx <-
        if (trustKeystore == null && blindTrust == false)
          IO.blocking(SSLContext.getDefault())
        else IO.blocking(buildSSLContext(TLS_PROTOCOL_TAG, trustKeystore, password))
      tcp_c <- TCPChannel.connect(host, port, group)
      ch <- IO(new TLSChannel(ssl_ctx, tcp_c))
      _ <- ch.ssl_initClient()
    } yield (ch)

    T

  }

}

class TLSChannel(val ctx: SSLContext, rch: TCPChannel) extends IOChannel {

  var f_SSL: SSLEngine = new SSLEngine(ctx.createSSLEngine())
  val TLS_PACKET_SZ = f_SSL.engine.getSession().getPacketBufferSize()
  val APP_PACKET_SZ = f_SSL.engine.getSession().getApplicationBufferSize()

  // how many packets we can consume per read() call N of TLS_PACKET_SZ  -> N of APP_PACKET_SZ
  val MULTIPLER = 4

  // prealoc carryover buffer, position getting saved between calls
  private[this] val IN_J_BUFFER = java.nio.ByteBuffer.allocate(TLS_PACKET_SZ * MULTIPLER)

  private[this] def doHandshakeClient() = {
    // val BUFF_SZ = ssl_engine.engine.getSession().getPacketBufferSize()

    var loop_cntr = 0 // to avoid issues with non-SSL sockets sending junk data

    val result = for {
      sequential_unwrap_flag <- Ref[IO].of(false)

      in_buf <- IO(ByteBuffer.allocate(TLS_PACKET_SZ))
      out_buf <- IO(ByteBuffer.allocate(TLS_PACKET_SZ))
      empty <- IO(ByteBuffer.allocate(0))

      _ <- f_SSL.wrap(empty, out_buf) *> IO(out_buf.flip) *> rch.write(out_buf)
      _ <- IO(out_buf.clear)
      loop = f_SSL.getHandshakeStatus().flatMap {
        _ match {
          case NEED_WRAP =>
            for {
              // data to check in_buff to prevent unnecessary read, and let to process the rest wih sequential unwrap
              pos_ <- IO(in_buf.position())
              lim_ <- IO(in_buf.limit())

              _ <- IO(out_buf.clear())
              result <- f_SSL.wrap(empty, out_buf)
              _ <- IO(out_buf.flip)
              // prevent reset to read if buffer has more data, now we can realy on underflow processing later
              _ <-
                if (pos_ > 0 && pos_ < lim_) IO.unit
                else sequential_unwrap_flag.set(false)

              handshakeStatus <- rch.write(out_buf) *> IO(result.getHandshakeStatus)
            } yield (handshakeStatus)

          case NEED_UNWRAP => {
            sequential_unwrap_flag.get.flatMap(_sequential_unwrap_flag =>
              if (_sequential_unwrap_flag == false)
                for {
                  _ <- IO(in_buf.clear)
                  _ <- IO(out_buf.clear)

                  nb <- rch
                    .readBuffer(in_buf, TLSChannel.READ_HANDSHAKE_TIMEOUT_MS)
                    .handleErrorWith(e =>
                      IO.raiseError(new TLSChannelError("TLS Handshake error timeout: " + e.toString))
                    )

                  _ <- if (nb == -1) IO.raiseError(new TLSChannelError("TLS Handshake, broken pipe")) else IO.unit

                  _ <- IO(in_buf.flip)
                  _ <- sequential_unwrap_flag.set(true)
                  result <- f_SSL.unwrap(in_buf, out_buf)
                } yield (result.getHandshakeStatus())
              else
                for {
                  _ <- IO(out_buf.clear)

                  pos <- IO(in_buf.position())
                  lim <- IO(in_buf.limit())

                  hStat <-
                    if (pos == lim) {
                      sequential_unwrap_flag.set(false) *> IO(NEED_UNWRAP)
                    } else {
                      for {
                        r <- f_SSL.unwrap(in_buf, out_buf)
                        _ <-
                          if (r.getStatus() == javax.net.ssl.SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                            for {

                              p1 <- IO(in_buf.position())
                              l1 <- IO(in_buf.limit())

                              // underflow read() append to the end, till BUF_SZ
                              _ <- IO(in_buf.position(l1))
                              _ <- IO(in_buf.limit(TLS_PACKET_SZ))

                              nb <- rch
                                .readBuffer(
                                  in_buf,
                                  TLSChannel.READ_HANDSHAKE_TIMEOUT_MS
                                )
                                .handleErrorWith(e =>
                                  IO.raiseError(new TLSChannelError("TLS Handshake error timeout: " + e.toString))
                                )

                              _ <-
                                if (nb == -1) IO.raiseError(new TLSChannelError("TLS Handshake, broken pipe"))
                                else IO.unit

                              p2 <- IO(in_buf.position()) // new limit
                              _ <- IO(in_buf.limit(p2))
                              _ <- IO(in_buf.position(p1)) // back to original position, we had before read

                              r <- f_SSL
                                .unwrap(in_buf, out_buf) // .map( r => { println( "SECOND " + r.toString); r })

                            } yield (r)

                          } else IO(r)

                      } yield (r.getHandshakeStatus)
                    }
                } yield (hStat)
            )
          }

          case NEED_TASK => f_SSL.getDelegatedTask() *> IO(NEED_TASK)

          case NOT_HANDSHAKING => IO(NOT_HANDSHAKING)

          case FINISHED => IO(FINISHED)

          case _ =>
            IO.raiseError(
              new TLSChannelError("unknown: getHandshakeStatus() - possible SSLEngine commpatibility problem")
            )
        }
      }
      r <- loop
        .iterateWhile(c => {
          loop_cntr = loop_cntr + 1; /*println( c.toString + " *** " + loop_cntr );*/
          c != FINISHED && loop_cntr < 300
        })

    } yield (r)
    result
  }

  private[this] def doHandshake(): IO[(HandshakeStatus, Chunk[Byte])] = {
    var loop_cntr = 0 // to avoid issues with non-SSL sockets sending junk data
    var temp = 0
    val result = for {
      sequential_unwrap_flag <- Ref[IO].of(false)

      in_buf <- IO(ByteBuffer.allocate(TLS_PACKET_SZ))
      out_buf <- IO(ByteBuffer.allocate(TLS_PACKET_SZ))
      empty <- IO(ByteBuffer.allocate(0))

      nbw <- rch
        .readBuffer(in_buf, TLSChannel.READ_HANDSHAKE_TIMEOUT_MS)
        .handleErrorWith(e => IO.raiseError(new TLSChannelError("TLS Handshake error timeout: " + e.toString)))

      _ <- if (nbw == -1) IO.raiseError(new TLSChannelError("TLS Handshake, broken pipe")) else IO.unit
      _ <- IO(in_buf.flip)
      _ <- f_SSL.unwrap(in_buf, out_buf)
      loop = f_SSL.getHandshakeStatus().flatMap {
        _ match {

          case NEED_WRAP =>
            for {
              _ <- IO(out_buf.clear)
              result <- f_SSL.wrap(empty, out_buf)
              _ <- IO(out_buf.flip)
              _ <- sequential_unwrap_flag.set(false)
              handshakeStatus <- rch.write(out_buf) >> IO(result.getHandshakeStatus)
            } yield (handshakeStatus)

          case NEED_UNWRAP => {
            sequential_unwrap_flag.get.flatMap(_sequential_unwrap_flag =>
              if (_sequential_unwrap_flag == false)
                for {
                  _ <- IO(in_buf.clear)
                  _ <- IO(out_buf.clear)
                  nbr <- rch
                    .readBuffer(in_buf, TLSChannel.READ_HANDSHAKE_TIMEOUT_MS)
                    .handleErrorWith(e =>
                      IO.raiseError(new TLSChannelError("TLS Handshake error timeout: " + e.toString))
                    )
                  _ <- if (nbr == -1) IO.raiseError(new TLSChannelError("TLS Handshake, broken pipe")) else IO.unit
                  _ <- IO { temp = nbr }
                  _ <- IO(in_buf.flip)
                  _ <- sequential_unwrap_flag.set(true)
                  result <- f_SSL.unwrap(in_buf, out_buf)
                } yield (result.getHandshakeStatus())
              else
                for {
                  _ <- IO(out_buf.clear)

                  pos <- IO(in_buf.position())
                  lim <- IO(in_buf.limit())

                  hStat <-
                    if (pos == lim)
                      sequential_unwrap_flag.set(false) >> IO(NEED_UNWRAP)
                    else
                      f_SSL.unwrap(in_buf, out_buf).map(_.getHandshakeStatus())
                } yield (hStat)
            )
          }

          case NEED_TASK => f_SSL.getDelegatedTask() *> IO(NEED_TASK)

          case NOT_HANDSHAKING => IO(NOT_HANDSHAKING)

          case FINISHED => IO(FINISHED)

          case _ =>
            IO.raiseError(
              new TLSChannelError("unknown: getHandshakeStatus() - possible SSLEngine commpatibility problem")
            )

        }
      }
      r <- loop
        .iterateWhile(c => { loop_cntr = loop_cntr + 1; c != FINISHED && loop_cntr < 300 })
        //.flatTap(_ =>
        //  IO.println("POS=" + in_buf.position() + "  " + temp + "  P4 = " + TLS_PACKET_SZ + "/" + APP_PACKET_SZ)
        //)
      // SSL data lefover issue.
      // very rare case when TLS handshake reads more then neccssary
      // this happens on very first connection upon restart only one time, when JVM is slow on first load/compilation
      // not really worth the efforts to fix, but always good to cover everything.
      _ <- IO(out_buf.clear())
      _ <- f_SSL.unwrap(in_buf, out_buf).iterateWhile { rs =>
        ((rs.getStatus() == SSLEngineResult.Status.OK) && (in_buf.remaining() != 0))
      }
      pos <- IO(out_buf.position())
      _ <- IO(out_buf.flip())
      _ <- IO(out_buf.limit(pos))
      leftOver <- IO(Chunk.byteBuffer(out_buf))

      _ <- IO(in_buf.limit(temp))
      // uncompleted data block, which cannot be decryted. It will be cached with TCPChanel#put, we will prepend it to next raw read.
      // the other part we managed to decrypt will go as leftOver to be passed to app logic read write.
      _ <- rch.put(in_buf)

    } yield ((r, leftOver))
    result
  }

  // close with TLS close_notify
  final def close(): IO[Unit] = {
    val result = for {
      _ <- IO(f_SSL.engine.getSession().invalidate())
      _ <- f_SSL.closeOutbound()
      empty <- IO(ByteBuffer.allocate(0))
      out <- IO(ByteBuffer.allocate(TLS_PACKET_SZ))
      loop = f_SSL.wrap(empty, out) *> f_SSL.isOutboundDone()
      _ <- loop.iterateUntil((status: Boolean) => status)
      _ <- IO(out.flip)
      _ <- rch.write(out)
      _ <- rch.close()
    } yield ()

    result.handleErrorWith(_ => IO.unit)
  }

  def ssl_initClent_h2(): IO[Unit] = {
    for {
      _ <- f_SSL.setUseClientMode(true)
      sslParameters <- IO(f_SSL.engine.getSSLParameters())
      _ <- IO(sslParameters.setApplicationProtocols(Array("h2")))
      _ <- IO(f_SSL.engine.setSSLParameters(sslParameters))
      x <- doHandshakeClient()
      _ <-
        if (x != FINISHED) {
          IO.raiseError(
            new TLSChannelError("TLS Handshake error, plain text connection?")
          )
        } else IO.unit
    } yield ()
  }


  def ssl_initClient(): IO[Unit] = {
    for {
      _ <- f_SSL.setUseClientMode(true)
      x <- doHandshakeClient()
      _ <-
        if (x != FINISHED) {
          IO.raiseError(new TLSChannelError("TLS Handshake error, plain text connection?"))
        } else IO.unit
    } yield ()

  }

  // Server side SSL Init
  // returns leftover chunk which needs to be used before we read chanel again.
  // for 99% there will be no leftover but under extreme load or upon JVM init it happens
  def ssl_init(): IO[Chunk[Byte]] = {
    for {
      _ <- f_SSL.setUseClientMode(false)
      x <- doHandshake()
      _ <-
        if (x._1 != FINISHED) {
          IO.raiseError(new TLSChannelError("TLS Handshake error, plain text connection?"))
        } else IO.unit
    } yield (x._2)
  }

  // Server side SSL Init with ALPN for H2 only
  // ALPN (Application Layer Protocol Negotiation) for http2
  // returns leftover chunk which needs to be used before we read chanel again.
  // for 99% there will be no leftover but under extreme load or upon JVM init it happens
  def ssl_init_h2(): IO[Chunk[Byte]] = {
    for {
      _ <- f_SSL.setUseClientMode(false)
      _ <- IO(f_SSL.engine.setHandshakeApplicationProtocolSelector((eng, list) => {
        if (list.asScala.find(_ == "h2").isDefined) "h2"
        else null
      }))

      x <- doHandshake()

      _ <-
        if (x._1 != FINISHED) {
          IO.raiseError(new TLSChannelError("TLS Handshake error, plain text connection?"))
        } else IO.unit
    } yield (x._2)
  }

  ////////////////////////////////////////////////////
  def write(in: ByteBuffer): IO[Int] = {

    val res = for {
      out <- IO(ByteBuffer.allocate(if (in.limit() > TLS_PACKET_SZ) in.limit() * 3 else TLS_PACKET_SZ * 3))
      //_ <- IO(out.clear)

      loop = for {
        res <- f_SSL.wrap(in, out)
        stat <- IO(res.getStatus())
        _ <-
          if (stat != SSLEngineResult.Status.OK) {
            IO.raiseError(new Exception("AsynchronousTlsByteChannel#write()! " + res.toString()))
          } else IO.unit
        rem <- IO(in.remaining())
      } yield (rem)
      _ <- loop.iterateWhile(_ != 0)
      _ <- IO(out.flip)

      nBytes <- rch.write(out)
    } yield (nBytes)
    res
  }

  def write(chunk: Chunk[Byte]): IO[Int] = write(chunk.toByteBuffer)

  private[netio] def readBuffer(out: ByteBuffer, timeoutMs: Int): IO[Int] = {
    val result = for {
      nb <- rch.readBuffer(IN_J_BUFFER, timeoutMs)
      _ <-
        if (nb == -1) IO.raiseError(new TLSChannelError("AsynchronousServerTlsByteChannel#read() with -1 "))
        else IO.unit

      _ <- IO(IN_J_BUFFER.flip)

      loop = for {
        res <- f_SSL.unwrap(IN_J_BUFFER, out)
        stat <- IO(res.getStatus())
        rem <-
          if (stat != SSLEngineResult.Status.OK) {
            if (stat == SSLEngineResult.Status.BUFFER_UNDERFLOW || stat == SSLEngineResult.Status.BUFFER_OVERFLOW)
              IO(0)
            else
              IO.raiseError(new TLSChannelError("AsynchronousTlsByteChannel#read() " + res.toString()))
          } else IO(IN_J_BUFFER.remaining())
      } yield (rem)
      _ <- loop.iterateWhile(_ != 0)
      save_pos <- IO(out.position())
      _ <- IO(out.flip)
      // ****compact, some data may be carried over for next read call
      _ <- IO(IN_J_BUFFER.compact)
    } yield (save_pos)

    result

  }

  def read(timeoutMs: Int): IO[Chunk[Byte]] = {
    for {
      bb <- IO(ByteBuffer.allocate(MULTIPLER * APP_PACKET_SZ))
      n <- readBuffer(bb, timeoutMs)
      chunk <- IO(Chunk.byteBuffer(bb))
    } yield (chunk)
  }
}
