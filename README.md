
<img src="quartz-h2.jpeg" width="84" title="quartz-h2"/>

[![Generic badge](https://img.shields.io/badge/quartz--h2-v0.4.6-blue)](https://repo1.maven.org/maven2/io/github/ollls/quartz-h2_3/0.4.6/)
[![Generic badge](https://img.shields.io/badge/Hello%20World-template-red)](https://github.com/ollls/json-template-qh2)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=ollls_quartz-h2&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=ollls_quartz-h2)

One more example/template: https://github.com/ollls/quartz-h2-gptapi

# Asynchronous Java NIO **http/2 TLS** packet streaming server/client..

TLS encryption implemented as scala CATS effects with ALPN h2 tag. Direct native translation of fs2 stream chunks into http2 packets, where http Request's data and http Response's data mapped directy to fs2 streams. Tested and optimized to produce highest possible TPS.(**120K TPS** on MacBook with h2load tool, see details below). Single java.util.concurrent.ForkJoinPool for JAVA NIO Socket Groups and for evalOn() with CATS Effects. Http/2 weights and dependency are not implemented, for performance reasons. 

```
"io.github.ollls" %% "quartz-h2" % "0.4.6"
```
to start server example with IO
```
sbt IO/run
```
to start server example based on ReaderT[IO, Request, Respose] with ability to use Reader environment.
```
sbt RIO/run
```
Server code for IO and RIO routes:
* https://github.com/ollls/quartz-h2/blob/main/examples/IO/src/main/scala/Run.scala
* https://github.com/ollls/quartz-h2/blob/main/examples/RIO/src/main/scala/Run.scala


### Basic server.
```scala
package example
import cats.effect.{IO, IOApp, ExitCode}
import io.quartz.QuartzH2Server
import io.quartz.http2.model.Method._
import io.quartz.http2._
import io.quartz.http2.routes.HttpRouteIO
import io.quartz.http2.model.{Headers, Method, ContentType, Request, Response}

object MyApp extends IOApp {
  val R: HttpRouteIO = {
    case req @ GET -> Root / "headers" => IO(Response.Ok().asText(req.headers.printHeaders))
  }
  def run(args: List[String]): IO[ExitCode] =
    for {
      ctx <- QuartzH2Server.buildSSLContext("TLS", "keystore.jks", "password")
      exitCode <- new QuartzH2Server("localhost", 8443, 16000, ctx).startIO(R, sync = false)
    } yield (exitCode)
}
```

### Webfilter support with Either[Response, Request]. Provide filter as a parameter QuartzH2Server()
```scala
val filter: WebFilter = (request: Request) =>
  IO(
    Either.cond(
     !request.uri.getPath().startsWith("/private"),
     request.hdr("test_tid" -> "ABC123Z9292827"),
     Response.Error(StatusCode.Forbidden).asText("Denied: " + request.uri.getPath())
    )
 )    
```
### Plain text http/2 - h2c, 175K TPS with 20 streams and 32 connections.
```scala
  //ssl context null will switch to h2c plain text
  exitCode <- new QuartzH2Server("localhost", 8443, 16000, null).startIO(R, filter, sync = false)
```  
### Http multipart support.
```scala
  case req @ POST -> Root / "mpart" =>
      MultiPart.writeAll(req, HOME_DIR) *> IO(Response.Ok())
```
### Sync mode with Java Socket Api on blocking pool.
```scala
  exitCode <- new QuartzH2Server("localhost", 8443, 60000, ctx).startIO( R, sync = false)
```
### Simple file retrieval.
```scala
 case GET -> Root / StringVar(file) =>
      val FOLDER_PATH = "/Users/user000/web_root/"
      val FILE = s"$file"
      val BLOCK_SIZE = 16000
      for {
        jpath <- IO(new java.io.File(FOLDER_PATH + FILE))
        jstream <- IO.blocking(new java.io.FileInputStream(jpath))
      } yield (Response
        .Ok()
        .asStream(fs2.io.readInputStream(IO(jstream), BLOCK_SIZE, true))
        .contentType(ContentType.contentTypeFromFileName(FILE)))
      } 
```

### Web site service.
```scala
    //your web site files in the folder "web" under web_root.    
    //browser path: https://localhost:8443/web/index.html
    case req @ GET -> "web" /: _ =>
      val FOLDER_PATH = "/Users/user000/web_root/"
      val BLOCK_SIZE = 16000
      for {
        reqPath <- IO(req.uri.getPath())
        jpath <- IO(new java.io.File(FOLDER_PATH + reqPath))
        jstream <- IO.blocking(new java.io.FileInputStream(jpath))
        fname <- IO(jpath.getName())
      } yield (Response
        .Ok()
        .asStream(fs2.io.readInputStream(IO(jstream), BLOCK_SIZE, true))
        .contentType(ContentType.contentTypeFromFileName(fname)))
  }
  ```
### Upload example.

POST https://localhost:8443/upload/op.jpeg
```scala
 case req@POST -> Root / "upload" /  StringVar(_) => 
    for {
        reqPath <- IO( Path( "/Users/user000/" + req.uri.getPath() ))
        u <- req.stream.through( Files[IO].writeAll( reqPath ) ).compile.drain
    } yield( Response.Ok().asText("OK"))
```


### Performance test tool.

https://nghttp2.org/documentation/h2load-howto.html
http2 spec compatibility tests: https://formulae.brew.sh/formula/h2spec

### How to run h2spec:

Start server with "sbt IO/run"
./h2spec http2 -h localhost -p 8443 -t -k
You should get:

Finished in 1.4891 seconds
94 tests, 93 passed, 1 skipped, 0 failed
