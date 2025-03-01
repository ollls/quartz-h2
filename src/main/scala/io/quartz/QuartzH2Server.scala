package io.quartz

import cats.effect.{IO, Ref, Deferred, ExitCode}

import fs2.{Stream, Chunk}

import io.quartz.http2.Http2Connection
import io.quartz.netio._

import java.net.InetSocketAddress
import java.net.SocketAddress

import java.nio.channels.AsynchronousChannelGroup

import java.nio.ByteBuffer

import io.quartz.http2.Constants._
import io.quartz.http2.Frames
import io.quartz.http2.Http2Settings

//import cats.implicits._
import org.typelevel.log4cats.Logger
import ch.qos.logback.classic.Level
import io.quartz.MyLogger._

import javax.net.ssl.SSLContext
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.KeyManagerFactory

import io.quartz.http2.model.{Headers, Request, Response}
import io.quartz.http2._
import io.quartz.http2.routes.HttpRoute
import io.quartz.http2.routes.Routes
import io.quartz.http2.routes.HttpRouteRIO
import io.quartz.http2.routes.HttpRouteIO
import io.quartz.http2.routes.WebFilter

import java.net._
import java.io._
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import scala.jdk.CollectionConverters.ListHasAsScala
import java.util.concurrent.ExecutorService
import scala.concurrent.ExecutionContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ForkJoinPool._
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit

case class HeaderSizeLimitExceeded(msg: String) extends Exception(msg)
case class BadProtocol(ch: IOChannel, msg: String) extends Exception(msg)

object QuartzH2Server {

  def setLoggingLevel(level: Level) = {
    val root = org.slf4j.LoggerFactory.getLogger("ROOT").asInstanceOf[ch.qos.logback.classic.Logger]
    root.setLevel(level)
  }

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

/** Quartz HTTP/2 server.
  * @param HOST
  *   the host address of the server
  * @param PORT
  *   the port number to bind to
  * @param h2IdleTimeOutMs
  *   the maximum idle time in milliseconds before a connection is closed
  * @param sslCtx
  *   the SSL context to use for secure connections, can be null for non-secure connections
  * @param incomingWinSize
  *   the initial window size for incoming flow control
  * @param onConnect
  *   callback function that is called when a connection is established, provides connectionId : Long as an argument
  * @param onDisconnect
  *   callback function that is called when a connection is terminated, provides connectionId : Long as an argument
  */
class QuartzH2Server(
    HOST: String,
    PORT: Int,
    h2IdleTimeOutMs: Int,
    sslCtx: Option[SSLContext],
    incomingWinSize: Int = 65535,
    onConnect: Long => IO[Unit] = _ => IO.unit,
    onDisconnect: Long => IO[Unit] = _ => IO.unit
) {

  val MAX_HTTP_HEADER_SZ = 16384
  val HTTP1_KEEP_ALIVE_MS = 20000

  val default_server_settings = new Http2Settings()
  var shutdownFlag = false

  // only HTTP11
  val header_pair = raw"(.{2,100}):\s+(.+)".r
  val http_line = raw"([A-Z]{3,8})\s+(.+)\s+(HTTP/.+)".r

  def shutdown = (for {
    _ <- IO { shutdownFlag = true }
    c <- TCPChannel.connect(HOST, PORT)
    _ <- c.close()
  } yield ()).handleError(_ => ())

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
      idRef: Ref[IO, Long],
      maxStreams: Int,
      keepAliveMs: Int,
      route: Request => IO[Option[Response]],
      leftOver: Chunk[Byte] = Chunk.empty[Byte]
  ): IO[Unit] = {
    for {
      id <- idRef.get
      buf <-
        if (leftOver.size > 0) IO(leftOver) else ch.read(HTTP1_KEEP_ALIVE_MS)
      test <- IO(buf.take(PrefaceString.length))
      testbb <- IO(test.toByteBuffer)
      isOK <- IO(Frames.checkPreface(testbb))
      _ <- Logger[IO].trace("doConnect() - Preface result: " + isOK)
      _ <-
        if (isOK == false) {

          doConnectUpgrade(ch, id, maxStreams, keepAliveMs, route, buf)

        } else
          (Http2Connection
            .make(ch, id, maxStreams, keepAliveMs, route, incomingWinSize, None)
            .flatMap(c =>
              IO(c)
                .bracket(c => onConnect(c.id) >> c.processIncoming(buf.drop(PrefaceString.length)))(c =>
                  onDisconnect(c.id) >> c.shutdown
                )
            ))

    } yield ()

  }

  def doConnectUpgrade(
      ch: IOChannel,
      id: Long,
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
      http11request <- IO(Some(Request(id, 1, headers11, res, ch.secure(), ch.sniServerNames(), emptyTH)))
      upd = headers11.get("upgrade").getOrElse("")
      _ <- Logger[IO].trace("doConnectUpgrade() - Upgrade = " + upd)
      clientPreface <-
        if (upd != "h2c") for {
          c <- Http11Connection.make(ch, id, keepAliveMs, route)
          refStart <- Ref.of[IO, Boolean](true)
          _ <- IO(c).bracket(c => onConnect(c.id) >> c.processIncoming(headers11, leftover, refStart).foreverM)(c =>
            onDisconnect(c.id) >> c.shutdown
          )
        } yield ()
        else
          for {
            _ <- Logger[IO].trace(("doConnectUpgrade() - h2c upgrade requested"))
            _ <- ch.write(ByteBuffer.wrap(protoSwitch().getBytes))
            clientPreface <- ch.read(HTTP1_KEEP_ALIVE_MS)
            bbuf <- IO(clientPreface.toByteBuffer)
            isOK <- IO(Frames.checkPreface(bbuf))
            c <-
              if (isOK) Http2Connection.make(ch, id, maxStreams, keepAliveMs, route, incomingWinSize, http11request)
              else
                IO.raiseError(
                  new BadProtocol(ch, "Cannot see HTTP2 Preface, bad protocol")
                )
            _ <- IO(c).bracket(c => onConnect(c.id) >> c.processIncoming(clientPreface.drop(PrefaceString.length)))(c =>
              onDisconnect(c.id) >> c.shutdown
            )
          } yield ()
    } yield ()
    R.void
  }

  ///////////////////////////////////
  def errorHandler(e: Throwable): IO[Unit] = {
    if (shutdownFlag == false) {
      e match {
        case BadProtocol(ch, e) =>
          Logger[IO].error("Cannot see HTTP2 Preface, bad protocol (1)") >>
            ch.write(Frames.mkGoAwayFrame(0, Error.PROTOCOL_ERROR, "".getBytes)).void
        case e: java.nio.channels.InterruptedByTimeoutException =>
          Logger[IO].info("Remote peer disconnected on timeout")
        case _ => Logger[IO].error("errorHandler: " + e.toString)
        /*>> IO(e.printStackTrace)*/
      }
    } else IO.unit
  }

  def hostName(address: SocketAddress) = {
    val ia = address.asInstanceOf[InetSocketAddress]
    ia.getHostString()
  }

  /** Starts an HTTP server with the given IO-based route and web filter.
    * @param pf
    *   The IO-based HTTP route to serve.
    * @param filter
    *   The web filter to apply to incoming requests, defaults to a filter that allows all requests through.
    * @param sync
    *   A boolean flag indicating whether the HTTP server should run synchronously or asynchronously.
    * @return
    *   An IO that produces the exit code of the HTTP server.
    */
  def startIO(pf: HttpRouteIO, filter: WebFilter = (r0: Request) => IO(Right(r0)), sync: Boolean): IO[ExitCode] =
    start(Routes.of(pf, filter), sync)

  /** Starts an HTTP server that handles requests based on the given routing function, using the `RIO` monad to handle
    * computations that depend on the environment `Env`.
    * @param env
    *   the environment required by the routing function.
    * @param pf
    *   the partial function that maps requests to `RIO` computations.
    * @param filter
    *   the filter to pre-process incoming requests (defaults to a pass-through filter).
    * @param sync
    *   whether to use a synchronous or asynchronous I/O model.
    * @return
    *   An IO that produces the exit code of the HTTP server.
    */
  def startRIO[Env](
      env: Env,
      pf: HttpRouteRIO[Env],
      filter: WebFilter = (r0: Request) => IO(Right(r0)),
      sync: Boolean
  ): IO[ExitCode] = {
    start(Routes.of(env, pf, filter), sync)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////
  // Can be used with JDK on Virtual Threads with tradional sync(now virtual) Java Sockets
  // val e = Executors.newVirtualThreadPerTaskExecutor()
  def startSync(
      R: HttpRoute,
      sync: Boolean,
      executor: ExecutorService,
      maxH2Streams: Int = 99
  ): IO[ExitCode] = {
    val ec = ExecutionContext.fromExecutor(executor)
    if (sslCtx.isDefined)
      run1(R, maxH2Streams, h2IdleTimeOutMs).evalOn(ec)
    else
      IO.raiseError(
        new Exception("Can not start Java Sockets without TLSContext, plain Java Sockets are not supported")
      )
  }

  //////////////////////////////////////////////////////////////////////////////////////////////
  // Usefull for running server on different thread pools, like FixedThreadPool, etc
  def startAsync(
      R: HttpRoute,
      sync: Boolean,
      executor: ExecutorService,
      numOfCores: Int = Runtime.getRuntime().availableProcessors(),
      maxH2Streams: Int = 99
  ): IO[ExitCode] = {
    val ec = ExecutionContext.fromExecutor(executor)
    if (sslCtx.isDefined)
      run0(executor, R, numOfCores, maxH2Streams, h2IdleTimeOutMs).evalOn(ec)
    else
      run3(executor, R, numOfCores, maxH2Streams, h2IdleTimeOutMs).evalOn(ec)
  }

  def start(R: HttpRoute, sync: Boolean): IO[ExitCode] = {
    val cores = Runtime.getRuntime().availableProcessors()
    val h2streams = cores * 2 // optimal setting tested with h2load
    // QuartzH2Server.setLoggingLevel( Level.OFF)
    if (sync == false) {
      val fjj = new ForkJoinWorkerThreadFactory {
        val num = new AtomicInteger();
        def newThread(pool: ForkJoinPool) = {
          val thread = defaultForkJoinWorkerThreadFactory.newThread(pool);
          thread.setDaemon(true);
          thread.setName("qh2-pool" + "-" + num.getAndIncrement());
          thread;
        }
      }
      val e = new java.util.concurrent.ForkJoinPool(cores, fjj, (t, e) => System.exit(0), false)
      // val e1 = java.util.concurrent.Executors.newFixedThreadPool(cores * 2);
      val ec = ExecutionContext.fromExecutor(e)

      /*
      if (sslCtx.isDefined)
        run0(e, R, cores, h2streams, h2IdleTimeOutMs).evalOn(ec)
      else
        run3(e, R, cores, h2streams, h2IdleTimeOutMs).evalOn(ec)*/

      run4(e, R, cores, h2streams, h2IdleTimeOutMs).evalOn(ec)

    } else {
      // Loom test commented out, just FYI
      // val e = Executors.newVirtualThreadPerTaskExecutor()
      // val ec = ExecutionContext.fromExecutor(e)
      run1(R, h2streams, h2IdleTimeOutMs)
    }

  }

  private def printSniName(names: Option[Array[String]]) = {
    names match {
      case Some(value) => value(0)
      case None        => "not provided"
    }
  }

  private def tlsPrint(c: TLSChannel) = {
    c.f_SSL.engine.getSession().getCipherSuite()
  }

  def run0(e: ExecutorService, R: HttpRoute, maxThreadNum: Int, maxStreams: Int, keepAliveMs: Int): IO[ExitCode] = {
    for {
      addr <- IO(new InetSocketAddress(HOST, PORT))
      _ <- Logger[IO].info("HTTP/2 TLS Service: QuartzH2 async mode (netio)")
      _ <- Logger[IO].info(s"Concurrency level(max threads): $maxThreadNum, max streams per conection: $maxStreams")
      _ <- Logger[IO].info(s"h2 idle timeout: $keepAliveMs Ms")
      _ <- Logger[IO].info(
        s"Listens: ${addr.getHostString()}:${addr.getPort().toString()}"
      )

      conId <- Ref[IO].of(0L)

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
          (IO(TLSChannel(sslCtx.get, ch))
            .flatMap(c => c.ssl_init_h2().map((c, _)))
            .flatTap(c =>
              Logger[IO].info(
                s"${c._1.ctx.getProtocol()} ${tlsPrint(c._1)} ${c._1.f_SSL.engine
                    .getApplicationProtocol()} tls-sni: ${printSniName(c._1.sniServerNames())}"
              )
            )
            .flatTap(_ => conId.update(_ + 1))
            .bracket(ch =>
              doConnect(ch._1, conId, maxStreams, keepAliveMs, R, ch._2).handleErrorWith(e => { errorHandler(e) })
            )(ch => ch._1.close())
            .handleErrorWith(e => { errorHandler(e) >> ch.close() })
            .start)
        )
        .iterateUntil(_ => shutdownFlag)
      _ <- IO(server_ch.close())
      _ <- Logger[IO].info("graceful server shutdown")

    } yield (ExitCode.Success)
  }

  def run1(R: HttpRoute, maxStreams: Int, keepAliveMs: Int): IO[ExitCode] = {
    for {
      addr <- IO(new InetSocketAddress(HOST, PORT))
      _ <- Logger[IO].info("HTTP/2 TLS Service: QuartzH2 sync mode (sockets)")
      _ <- Logger[IO].info(s"Max number of H2 streams per conection: $maxStreams")
      _ <- Logger[IO].info(s"h2 idle timeout: $keepAliveMs Ms")
      _ <- Logger[IO].info(s"Listens: ${addr.getHostString()}:${addr.getPort().toString()}")

      conId <- Ref[IO].of(0L)

      server_ch <- IO(
        sslCtx.get.getServerSocketFactory().createServerSocket(PORT, 0, addr.getAddress()).asInstanceOf[SSLServerSocket]
      )

      accept = IO
        .blocking(server_ch.accept().asInstanceOf[SSLSocket])
        .flatTap(c =>
          IO {
            c.setUseClientMode(false);
            c.setHandshakeApplicationProtocolSelector((eng, list) => {

              if (list.asScala.find(_ == "h2").isDefined) "h2"
              else if (list.asScala.find(_ == "http/1.1").isDefined) "http/1.1"
              else null
            })
          }
        )
        .flatMap(c => IO(new SocketChannel(c)))
        .flatTap(c => Logger[IO].info(s"Connect from remote peer: ${c.socket.getInetAddress().toString()}"))
      _ <- accept
        .flatTap(_ => conId.update(_ + 1))
        .flatMap(ch =>
          (IO(ch)
            .bracket(ch =>
              doConnect(ch, conId, maxStreams, keepAliveMs, R, Chunk.empty[Byte]).handleErrorWith(e => {
                errorHandler(e)
              })
            )(ch => ch.close())
            .start)
        )
        .iterateUntil(_ => shutdownFlag)
      _ <- IO(server_ch.close())
      _ <- Logger[IO].info("graceful server shutdown")

    } yield (ExitCode.Success)
  }

  def run3(e: ExecutorService, R: HttpRoute, maxThreadNum: Int, maxStreams: Int, keepAliveMs: Int): IO[ExitCode] = {
    for {
      addr <- IO(new InetSocketAddress(HOST, PORT))
      _ <- Logger[IO].info("HTTP/2 h2c service: QuartzH2 async mode (netio)")
      _ <- Logger[IO].info(s"Concurrency level(max threads): $maxThreadNum, max streams per conection: $maxStreams")
      _ <- Logger[IO].info(s"h2c idle timeout: $keepAliveMs Ms")
      _ <- Logger[IO].info(
        s"Listens: ${addr.getHostString()}:${addr.getPort().toString()}"
      )
      group <- IO(
        AsynchronousChannelGroup.withThreadPool(e)
      )

      conId <- Ref[IO].of(0L)

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
        .flatTap(_ => conId.update(_ + 1))
        .flatMap(ch =>
          IO(ch)
            .bracket(ch =>
              doConnect(ch, conId, maxStreams, keepAliveMs, R, Chunk.empty[Byte]).handleErrorWith(e => {
                errorHandler(e)
              })
            )(_.close())
            .start
        )
        .iterateUntil(_ => shutdownFlag)
      _ <- IO(server_ch.close())
      _ <- Logger[IO].info("graceful server shutdown")

    } yield (ExitCode.Success)
  }

  import sh.blake.niouring.{IoUringServerSocket, IoUring}
  import io.quartz.netio.IOURingChannel
  import sh.blake.niouring.util.NativeLibraryLoader
  import java.util.concurrent.Executors
  import cats.implicits._
  import scala.concurrent.ExecutionContextExecutorService

 
  def run4(e: ExecutorService, R: HttpRoute, maxThreadNum: Int, maxStreams: Int, keepAliveMs: Int): IO[ExitCode] = {
    for {
      _ <- Logger[IO].error("HTTP/2 h2c service: QuartzH2 async mode (nio_uring)")

      rings <- Ref[IO].of[Vector[IoUring]](Vector.empty[IoUring])

      conId <- Ref[IO].of(0L)

      serverSocket <- IO(new IoUringServerSocket(8080))

      _ <- Logger[IO].info(s"Listens: 8080")

      acceptURing <- IO(new IoUring(512))

      loop = for {
        a <- IOURingChannel.accept(acceptURing, serverSocket)
        (ring, socket) = a
        _ <- Logger[IO].info(s"Connect from remote peer: ${socket.ipAddress()}")

        _ <- IO(IOURingChannel(new IoUring(4096), socket))
          .flatMap(ch =>
            IO(ch)
              .bracket(ch =>
                doConnect(ch, conId, maxStreams, keepAliveMs, R, Chunk.empty[Byte]).handleErrorWith(e => {
                  errorHandler(e)
                })
              )(c => { IO.println("CLOSE") *> c.close() *> IO(c.ring.close()) })
              .start
          )
      } yield ()

      _ <- loop.iterateUntil(_ => shutdownFlag)

      _ <- IO(serverSocket.close())
      _ <- Logger[IO].info("graceful server shutdown")

    } yield (ExitCode.Success)

  }

}
