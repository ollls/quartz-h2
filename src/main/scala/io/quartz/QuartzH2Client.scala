package io.quartz

import cats.effect.IO
import cats.implicits._

import io.quartz.netio
import io.quartz.http2.model.Headers
import io.quartz.http2.model.StatusCode
import io.quartz.netio.{IOChannel, TCPChannel, TLSChannel}

import java.net.URI
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.KeyManagerFactory
import java.nio.channels.{AsynchronousChannelGroup, AsynchronousSocketChannel}
import java.nio.ByteBuffer
import java.security.KeyStore

import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import java.io.FileInputStream
import java.io.File
import io.quartz.http2.Http2ClientConnection

import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import io.quartz.MyLogger._

object QuartzH2Client {

  val TLS_PROTOCOL_TAG = "TLSv1.2"

  private def loadDefaultKeyStore(): KeyStore = {
    val relativeCacertsPath =
      "/lib/security/cacerts".replace("/", File.separator);
    val filename = System.getProperty("java.home") + relativeCacertsPath;
    val is = new FileInputStream(filename);

    val keystore = KeyStore.getInstance(KeyStore.getDefaultType());
    val password = "changeit";
    keystore.load(is, password.toCharArray());

    keystore;
  }

  /** Builds an SSL context for use in an HTTP/2 client connection.
    * @param protocol
    *   The SSL/TLS protocol to use. E.g. "TLSv1.2".
    * @param JKSkeystore
    *   The path to the JKS keystore file. If null, a default keystore will be used.
    * @param password
    *   The password to use for the keystore. Ignored if JKSkeystore is null.
    * @param blindTrust
    *   Whether to blindly trust all SSL/TLS certificates. Default is false.
    * @return
    *   A new SSL context for use in an HTTP/2 client connection.
    */
  def buildSSLContext(
      protocol: String,
      JKSkeystore: String = null,
      password: String = "",
      blindTrust: Boolean = false
  ) = {
    val sslContext: SSLContext = SSLContext.getInstance(protocol)

    val keyStore = if (JKSkeystore == null) {
      loadDefaultKeyStore()
    } else {
      val keyStore: KeyStore = KeyStore.getInstance("JKS")
      val ks = new java.io.FileInputStream(JKSkeystore)
      keyStore.load(ks, password.toCharArray())
      keyStore
    }

    val trustMgrs = if (blindTrust == true) {
      Array[TrustManager](new X509TrustManager() {
        def getAcceptedIssuers(): Array[X509Certificate] = null
        def checkClientTrusted(c: Array[X509Certificate], a: String): Unit = ()
        def checkServerTrusted(c: Array[X509Certificate], a: String): Unit = ()
      })
    } else {
      val tmf = TrustManagerFactory.getInstance(
        TrustManagerFactory.getDefaultAlgorithm()
      )
      tmf.init(keyStore)
      tmf.getTrustManagers()
    }

    val pwd = if (JKSkeystore == null) "changeit" else password

    val kmf =
      KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(keyStore, pwd.toCharArray())
    sslContext.init(kmf.getKeyManagers(), trustMgrs, null);

    sslContext
  }

  def connectTLS_alpn_h2(
      host: String,
      port: Int,
      socketGroup: AsynchronousChannelGroup,
      ctx: SSLContext
  ): IO[TLSChannel] = {
    val T = for {
      _ <- Logger[IO].debug(s"Client: Connecting to $host:$port")
      address <- IO(new java.net.InetSocketAddress(host, port))
      ch <- IO(
        if (socketGroup == null) AsynchronousSocketChannel.open()
        else AsynchronousSocketChannel.open(socketGroup)
      )

      ch <- TCPChannel.connect(host, port, socketGroup)
      tls_ch <- IO(new TLSChannel(ctx, ch)).flatTap(c => c.ssl_initClent_h2(host))

      alpn_tag0 <- IO(tls_ch.f_SSL.engine.getApplicationProtocol())
      alpn_tag <- IO(if (alpn_tag0 == null) "not selected or empty" else alpn_tag0)
      _ <- Logger[IO].trace(s"Client: Server ALPN: $alpn_tag")
      _ <- Logger[IO].debug("Client: ALPN, server accepted to use h2").whenA(alpn_tag == "h2")
      _ <- IO
        .raiseError(
          new Exception(
            s"Client: $host:$port failure to negotiate ALPN h2 protocol, ALPN tags were not present or not recognized"
          )
        )
        .whenA(alpn_tag != "h2")

    } yield (tls_ch)
    T
  }

  private def connect(
      u: URI,
      ctx: SSLContext,
      socketGroup: AsynchronousChannelGroup = null
  ): IO[IOChannel] = {
    val port = if (u.getPort == -1) 443 else u.getPort
    if (u.getScheme().equalsIgnoreCase("https")) {
      connectTLS_alpn_h2(
        u.getHost(),
        port,
        socketGroup,
        ctx
      )
    } else if (u.getScheme().equalsIgnoreCase("http")) {
      TCPChannel.connect(u.getHost(), port, socketGroup)
    } else
      IO.raiseError(
        new Exception("HttpConnection: Unsupported scheme - " + u.getScheme())
      )
  }

  /** Opens an HTTP/2 client connection to the specified host URI using the provided SSL context.
    * @param hostURI
    *   The URI of the host to connect to.
    * @param timeOutMs
    *   The timeout in milliseconds for the connection attempt.
    * @param ctx
    *   The SSL context to use for the connection.
    * @param incomingWindowSize
    *   The size in bytes of the incoming flow control window.
    */
  def open(
      hostURI: String,
      timeOutMs: Int,
      ctx: SSLContext,
      incomingWindowSize: Int = 65535,
      socketGroup: AsynchronousChannelGroup = null
  ): IO[Http2ClientConnection] = for {
    u <- IO(new URI(hostURI))
    io_ch <- QuartzH2Client.connect(
      u,
      ctx
    )
    c_h <- Http2ClientConnection.make(io_ch, u, timeOutMs, incomingWindowSize)
    settings <- c_h.H2_ClientConnect()
  } yield (c_h)

}
