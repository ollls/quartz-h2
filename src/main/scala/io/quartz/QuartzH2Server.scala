package io.quartz

import cats.effect.{IO, IOApp, Deferred, ExitCode}

import fs2.{Stream, Chunk, Pull}
import fs2.text

import io.quartz.http2.Http2Connection
import io.quartz.netio._

import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.channels.Channel
import java.nio.channels.{
  AsynchronousChannelGroup,
  AsynchronousServerSocketChannel,
  AsynchronousSocketChannel,
  CompletionHandler
}
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import io.quartz.http2.Constants._
import io.quartz.http2.Frames
import io.quartz.http2.Http2Settings

import cats.implicits._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import io.quartz.MyLogger._

import javax.net.ssl.SSLContext
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.KeyManagerFactory

import scala.concurrent.duration.FiniteDuration
import concurrent.duration.DurationInt

import java.nio.file.Files
import io.quartz.http2.model.{Headers, Method, ContentType, Request, Response}
import io.quartz.http2.model.Method._
import io.quartz.http2._
import io.quartz.http2.routes.RIO
import io.quartz.http2.routes.HttpRoute
import io.quartz.http2.routes.Routes
import io.quartz.http2.routes.HttpRouteRIO
import io.quartz.http2.routes.HttpRouteIO

import java.net._
import java.io._
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SSLSocket
import scala.jdk.CollectionConverters.ListHasAsScala
import java.util.concurrent.ExecutorService
import scala.concurrent.ExecutionContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ForkJoinPool._
import java.util.concurrent.ForkJoinPool

case class HeaderSizeLimitExceeded(msg: String) extends Exception(msg)
case class BadProtocol(ch: IOChannel, msg: String) extends Exception(msg)

object QuartzH2Server {
  def buildSSLContext(
      protocol: String,
      JKSkeystore: String,
      password: String
  ): IO[SSLContext] = {

    val ctx = IO.blocking {
      val sslContext: SSLContext = SSLContext.getInstance(protocol)
      val keyStore: KeyStore = KeyStore.getInstance("JKS")
      val ks = new java.io.FileInputStream(JKSkeystore)
      if (ks == null)
        IO.raiseError(
          new java.io.FileNotFoundException(
            JKSkeystore + " keystore file not found."
          )
        )
      keyStore.load(ks, password.toCharArray())
      val tmf: TrustManagerFactory = TrustManagerFactory.getInstance(
        TrustManagerFactory.getDefaultAlgorithm()
      )
      tmf.init(keyStore)
      val kmf =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
      kmf.init(keyStore, password.toCharArray())
      sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
      sslContext
    }
    ctx
  }
}

class QuartzH2Server(HOST: String, PORT: Int, h2IdleTimeOutMs: Int, sslCtx: SSLContext) {

  // def this(HOST: String) = this(HOST, 8080, 20000, null)

  val MAX_HTTP_HEADER_SZ = 16384
  val HTTP1_KEEP_ALIVE_MS = 20000

  // val HOST = "localhost"
  // val PORT = 8443
  // val SERVER = "127.0.0.1"

  val default_server_settings = new Http2Settings()

  val header_pair = raw"(.{2,100}):\s+(.+)".r
  val http_line = raw"([A-Z]{3,8})\s+(.+)\s+(HTTP/.+)".r

  private def parseHeaderLine(line: String, hdrs: Headers): Headers =
    line match {
      case http_line(method, path, _) =>
        hdrs ++ Headers(
          ":method" -> method,
          ":path" -> path,
          ":scheme" -> "http"
        ) // FIX TBD - no schema for now, ":scheme" -> prot)
      case header_pair(attr, value) => hdrs + (attr.toLowerCase -> value)
      case _                        => hdrs
    }

  def protoSwitch() = {
    val CRLF = "\r\n"
    val r = new StringBuilder
    r ++= "HTTP/1.1 101 Switching Protocols" + CRLF
    r ++= "Connection: Upgrade" + CRLF
    r ++= "Upgrade: h2c" + CRLF + CRLF
    r.toString()
  }

  def responseString() = {
    val contLen = 0
    val CRLF = "\r\n"
    val TAG = "quartz"
    val dfmt = new java.text.SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss")

    val r = new StringBuilder
    r ++= "HTTP/1.1 200" + CRLF // + code.value.toString + CRLF
    r ++= "Date: " + dfmt.format(new java.util.Date()) + " GMT" + CRLF
    r ++= "Server: " + TAG + CRLF
    r ++= "Content-Length: " + contLen.toString() + CRLF

    r ++= CRLF

    r.toString()

  }

  def responseStringNo11() = {
    val contLen = 0
    val CRLF = "\r\n"
    val TAG = "quartz"
    val dfmt = new java.text.SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss")

    val r = new StringBuilder
    r ++= "HTTP/1.1 505" + CRLF // + code.value.toString + CRLF
    r ++= "Date: " + dfmt.format(new java.util.Date()) + " GMT" + CRLF
    r ++= "Server: " + TAG + CRLF
    r ++= "Content-Length: " + contLen.toString() + CRLF

    r ++= CRLF

    r.toString()

  }

  def getHttpHeaderAndLeftover(chunk: Chunk[Byte]): (Headers, Chunk[Byte]) = {
    var cur = chunk
    var stop = false
    var complete = false
    var hdrs = Headers()

    while (stop == false) {
      val i_opt = cur.indexWhere(_ == 0x0d)
      i_opt match {
        case Some(i) =>
          val line = cur.take(i)
          hdrs = parseHeaderLine(new String(line.toArray), hdrs)
          cur = cur.drop(i + 2)
          if (line.size == 0) {
            complete = true;
            stop = true;
          }
        case None => stop = true
      }
    }

    // won't use stream to fetch all headers, must be present at once in one bufer read ops.
    if (complete == false)
      IO.raiseError(new HeaderSizeLimitExceeded(""))
    (hdrs, cur)
  }

  def doConnect(
      ch: IOChannel,
      maxStreams: Int,
      keepAliveMs: Int,
      route: Request => IO[Option[Response]],
      leftOver: Chunk[Byte] = Chunk.empty[Byte]
  ): IO[Unit] = {
    for {
      buf <-
        if (leftOver.size > 0) IO(leftOver) else ch.read(HTTP1_KEEP_ALIVE_MS)

      test <- IO(buf.take(PrefaceString.length))

      testbb <- IO(test.toByteBuffer)
      isOK <- IO(Frames.checkPreface(testbb))
      _ <- Logger[IO].trace("doConnect() - Preface result: " + isOK)
      _ <-
        if (isOK == false) {

          doConnectUpgrade(ch, maxStreams, keepAliveMs, route, buf)

        } else
          (Http2Connection
              .make(ch, maxStreams, keepAliveMs, route, None)
              .flatMap(c => IO(c).bracket(c => c.processIncoming(buf.drop(PrefaceString.length)))(_.shutdown))
            // c.processIncoming(buf.drop(PrefaceString.length )) /*>> IO.println("WAIT on close") >> IO.sleep(3.second)*/
          )

    } yield ()

  }

  def doConnectUpgrade(
      ch: IOChannel,
      maxStreams: Int,
      keepAliveMs: Int,
      route: Request => IO[Option[Response]],
      buf: Chunk[Byte]
  ): IO[Unit] = {
    val R = for {
      _ <- Logger[IO].trace("doConnectUpgrade()")
      hb <- IO(getHttpHeaderAndLeftover(buf))
      leftover = hb._2
      headers11 = hb._1
      contentLen = headers11.get("Content-Length").getOrElse("0").toLong

      s1 <- IO(
        Stream[IO, Chunk[Byte]](leftover).flatMap(c0 => Stream.chunk(c0))
      )
      s2 <- IO(
        Stream
          .repeatEval(ch.read(HTTP1_KEEP_ALIVE_MS))
          .flatMap(c0 => Stream.chunk(c0))
      )
      res <- IO((s1 ++ s2).take(contentLen))

      emptyTH <- Deferred[IO, Headers] // no trailing headers for 1.1
      _ <- emptyTH.complete(Headers()) // complete with empty
      http11request <- IO(Some(Request(headers11, res, emptyTH)))
      upd = headers11.get("upgrade").getOrElse("")
      _ <- Logger[IO].trace("doConnectUpgrade() - Upgrade = " + upd)
      clientPreface <-
        if (upd == "h2c") {
          Logger[IO].trace("doConnectUpgrade() - h2c upgrade requested") *>
            ch.write(ByteBuffer.wrap(protoSwitch().getBytes)) *>
            ch.read(
              HTTP1_KEEP_ALIVE_MS
            ) // clent preface and remote peer/client setting array  !!!!FIX NEDED
        } else
          IO.raiseError(new BadProtocol(ch, "HTTP2 Upgrade Request Denied"))
      bbuf <- IO(clientPreface.toByteBuffer)
      isOK <- IO(Frames.checkPreface(bbuf))
      c <-
        if (isOK) Http2Connection.make(ch, maxStreams, keepAliveMs, route, http11request)
        else
          IO.raiseError(
            new BadProtocol(ch, "Cannot see HTTP2 Preface, bad protocol")
          )
      _ <- IO(c).bracket(c => c.processIncoming(clientPreface.drop(PrefaceString.length)))(_.shutdown)
    } yield ()
    R.void
  }

  ///////////////////////////////////
  def errorHandler(e: Throwable) = {
    e match {
      case BadProtocol(ch, e) =>
        ch.write(ByteBuffer.wrap(responseStringNo11().getBytes)) >> IO.println(
          e.toString
        )
      case e: java.nio.channels.InterruptedByTimeoutException =>
        Logger[IO].info("Remote peer disconnected on timeout")
      case _ => Logger[IO].error("errorHandler: " + e.toString)
      /*>> IO(e.printStackTrace)*/
    }
  }

  def hostName(address: SocketAddress) = {
    val ia = address.asInstanceOf[InetSocketAddress]
    ia.getHostString()
  }

  def startIO(pf: HttpRouteIO, sync: Boolean): IO[ExitCode] = {
    start(Routes.of(pf), sync)

    //   val T1: HttpRoute = (request: Request) => pf.lift(request) match {
    //      case Some(c) => c.flatMap(r => (IO(Option(r))))
    //      case None    => (IO(None))
    //    }

    //    val ret : IO[ExitCode] = start( T1 )
    //    ret

  }

  def startRIO[Env](env: Env, pf: HttpRouteRIO[Env]): IO[ExitCode] = {
    val fjj = new ForkJoinWorkerThreadFactory {
      val num = new AtomicInteger();
      def newThread(pool: ForkJoinPool) = {
        val thread = defaultForkJoinWorkerThreadFactory.newThread(pool);
        thread.setDaemon(true);
        thread.setName("qh2-pool" + "-" + num.getAndIncrement());
        thread;
      }
    }
    val cores = Runtime.getRuntime().availableProcessors()
    val h2streams = cores * 2 // optimal setting tested with h2load
    val e = new java.util.concurrent.ForkJoinPool(cores, fjj, (t, e) => System.exit(0), false)
    val ec = ExecutionContext.fromExecutor(e)

    val routeIO: Request => RIO[Env, Option[Response]] = (request: Request) =>
      pf.lift(request) match {
        case Some(c) => c.flatMap(r => RIO.liftIO(IO(Option(r))))
        case None    => RIO.liftIO(IO(None))
      }

    val routeIO2 = (request: Request) => routeIO(request).run(env)

    run0(e, routeIO2, cores, h2streams, h2IdleTimeOutMs).evalOn(ec)
  }

  def start(R: HttpRoute, sync: Boolean): IO[ExitCode] = {
    val fjj = new ForkJoinWorkerThreadFactory {
      val num = new AtomicInteger();
      def newThread(pool: ForkJoinPool) = {
        val thread = defaultForkJoinWorkerThreadFactory.newThread(pool);
        thread.setDaemon(true);
        thread.setName("qh2-pool" + "-" + num.getAndIncrement());
        thread;
      }
    }
    val cores = Runtime.getRuntime().availableProcessors()
    val h2streams = cores * 2 // optimal setting tested with h2load
    val e = new java.util.concurrent.ForkJoinPool(cores, fjj, (t, e) => System.exit(0), false)
    val ec = ExecutionContext.fromExecutor(e)
    if (sync == false) {
      run0(e, R, cores, h2streams, h2IdleTimeOutMs).evalOn(ec)
    } else {
      //Loom test commented out, just FYI
      //val e = Executors.newVirtualThreadPerTaskExecutor()
      //val ec = ExecutionContext.fromExecutor(e)
      run1(e, R, cores, h2streams, h2IdleTimeOutMs).evalOn(ec)
    }
  }

  def run0(e: ExecutorService, R: HttpRoute, maxThreadNum: Int, maxStreams: Int, keepAliveMs: Int): IO[ExitCode] = {
    for {
      addr <- IO(new InetSocketAddress(HOST, PORT))
      _ <- Logger[IO].info("HTTP/2 TLS Service: QuartzH2 ( async - Java NIO")
      _ <- Logger[IO].info(s"Concurrency level(max threads): $maxThreadNum, max streams per conection: $maxStreams")
      _ <- Logger[IO].info(s"Fast mode (stream priority switched off): ${Http2Connection.FAST_MODE}")
      _ <- Logger[IO].info(s"H2 Idle Timeout: $keepAliveMs Ms")
      _ <- Logger[IO].info(
        s"Listens: ${addr.getHostString()}:${addr.getPort().toString()}"
      )
      group <- IO(
        AsynchronousChannelGroup.withThreadPool(e)
      )
      server_ch <- IO(
        group.provider().openAsynchronousServerSocketChannel(group).bind(addr)
      )
      accept = Logger[IO].debug("Wait on accept") >> TCPChannel
        .accept(server_ch)
        .flatTap(c =>
          Logger[IO].info(
            s"Connect from remote peer: ${hostName(c.ch.getRemoteAddress())}"
          )
        )

      _ <- accept
        .flatMap(ch =>
          (IO(TLSChannel(sslCtx, ch))
            .flatMap(c => c.ssl_init_h2().map((c, _)))
            .bracket(ch => doConnect(ch._1, maxStreams, keepAliveMs, R, ch._2))(ch => ch._1.close())
            .handleErrorWith(e => { errorHandler(e) })
            .start)
        )
        .foreverM

    } yield (ExitCode.Success)
  }

  /*
    def doConnect(
      ch: IOChannel,
      maxStreams: Int,
      keepAliveMs: Int,
      route: Request => IO[Option[Response]],
      leftOver: Chunk[Byte] = Chunk.empty[Byte]
  )
   */

  def run1( e : ExecutorService, R: HttpRoute, maxThreadNum: Int, maxStreams: Int, keepAliveMs: Int): IO[ExitCode] = {
    for {
      addr <- IO(new InetSocketAddress(HOST, PORT))
      _ <- Logger[IO].info("HTTP/2 TLS Service: QuartzH2 ( sync - Java Socket )")
      _ <- Logger[IO].info(s"Listens: ${addr.getHostString()}:${addr.getPort().toString()}")

      // sslCtx

      // server_ch <- IO(new ServerSocket(PORT, 0, addr.getAddress))
      server_ch <- IO(
        sslCtx.getServerSocketFactory().createServerSocket(PORT, 0, addr.getAddress()).asInstanceOf[SSLServerSocket]
      )

      accept = IO(server_ch.accept().asInstanceOf[SSLSocket])
        .flatTap(c =>
          IO {
            c.setUseClientMode(false);
            c.setHandshakeApplicationProtocolSelector((eng, list) => {
              if (list.asScala.find(_ == "h2").isDefined) "h2"
              else null
            })
          }
        )
        .flatMap(c => IO(new SocketChannel(c)))
        .flatTap(c => Logger[IO].info(s"Connect from remote peer: ${c.socket.getInetAddress().toString()}"))

      // accept = Logger[IO].debug("Wait on accept") >> SocketChannel
      //  .accept(server_ch)
      //  .flatTap(c => Logger[IO].info(s"Connect from remote peer: ${c.socket.getInetAddress().toString()}"))
      _ <- accept
        .flatMap(ch =>
          (IO(ch)
            .bracket(ch => doConnect(ch, maxStreams, keepAliveMs, R, Chunk.empty[Byte]))(ch => ch.close())
            .handleErrorWith(e => { errorHandler(e) })
            .start)
        )
        .foreverM

    } yield (ExitCode.Success)
  }

  /*
  def run_async(args: List[String]): IO[ExitCode] = {
    for {
      addr <- IO(new InetSocketAddress(HOST, PORT))
      _ <- Logger[IO].info("HTTP/2 TCP Service")
      _ <- Logger[IO].info(s"Fast mode: ${Http2Connection.FAST_MODE}")
      _ <- Logger[IO].info(
        s"Listens: ${addr.getHostString()}:${addr.getPort().toString()}"
      )
      group <- IO(
        AsynchronousChannelGroup.withThreadPool(Executors.newFixedThreadPool(4))
      )
      server_ch <- IO(
        group.provider().openAsynchronousServerSocketChannel(group).bind(addr)
      )
      accept = Logger[IO].debug("Wait on accept") >> TCPChannel
        .accept(server_ch)
        .flatTap(c =>
          Logger[IO].info(
            s"Connect from remote peer: ${hostName(c.ch.getRemoteAddress())}"
          )
        )
      _ <- accept
        .flatMap(ch =>
          (IO(ch)
            .bracket(ch => doConnect(ch, 30, 20000, R))(ch => ch.close())
            .handleErrorWith(e => { errorHandler(e) })
            .start)
        )
        .foreverM

    } yield (ExitCode.Success)
  }*/
}
