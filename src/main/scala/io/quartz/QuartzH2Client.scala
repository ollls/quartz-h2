package io.quartz

import cats.effect.IO

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

object QuartzH2Client {

  def apply(
      hostURI: String,
      incomingWindowSize: Int = 65535,
      ctx: SSLContext,
      socketGroup: AsynchronousChannelGroup = null
  ): IO[Http2ClientConnection] = open(
    hostURI,
    incomingWindowSize,
    ctx,
    socketGroup
  )

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

  def buildSSLContext(
      protocol: String,
      JKSkeystore: String,
      password: String,
      blindTrust: Boolean
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
      address <- IO(new java.net.InetSocketAddress(host, port))
      ch <- IO(
        if (socketGroup == null) AsynchronousSocketChannel.open()
        else AsynchronousSocketChannel.open(socketGroup)
      )

      ch <- TCPChannel.connect(host, port, socketGroup)
      tls_ch <- IO(new TLSChannel(ctx, ch)).flatTap(c => c.ssl_initClent_h2())
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

  def open(
      hostURI: String,
      incomingWindowSize: Int = 65535,
      ctx: SSLContext,
      socketGroup: AsynchronousChannelGroup = null
  ): IO[Http2ClientConnection] = for {
    u <- IO(new URI(hostURI))
    io_ch <- QuartzH2Client.connect(
      u,
      ctx
    )
    c_h <- Http2ClientConnection.make(io_ch, u, incomingWindowSize)
    settings <- c_h.H2_ClientConnect()
  } yield (c_h)

}
