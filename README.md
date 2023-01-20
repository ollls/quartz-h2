
[![Generic badge](https://img.shields.io/badge/quartz--h2-v0.2.1.1-blue)](https://repo1.maven.org/maven2/io/github/ollls/quartz-h2_3/0.2.1.1)

100% asyncronous Java NIO based implementation of http/2 packet streaming server with TLS encryption implemented as scala CATS effect.
Direct native translation of fs2 stream chunks into http2 packets and vice versa, packets to fs2 chunks (inbound and outbound).
Tested and optimized to produce highest possible TPS.(**120K TPS** on MacBook with h2load tool, see details below). It uses single java.util.concurrent.ForkJoinPool for JAVA NIO Socket Groups and for evalOn() with CATS Effects. Http/2 weights and dependecy are not implemented, for performance reasons. 


```
"io.github.ollls" %% "quartz-h2" % "0.2.1"
"io.github.ollls" %% "quartz-h2" % "0.2.1.1" - with cats effects 3.4.5 and fs2 3.4.0
```
* Template project with quartz-h2 from sonata repo: https://github.com/ollls/json-template-qh2<br>
* ZIO2 port: https://github.com/ollls/zio-quartz-h2

### Pending updates not included in release

 01/20/2023 New webfilter support with Either[Response, Request]

```scala
 val filter: WebFilter = (request: Request) =>
   IO(
      Either.cond(
        !request.uri.getPath().endsWith("na.txt"),
        request.hdr("test_tid" -> "ABC123Z9292827"),
        Response.Error(StatusCode.Forbidden).asText("Denied: " + request.uri.getPath())
      )
    )
    
    ...
    //How to check/debug headers
    case req @ GET -> Root / "headers" => IO(Response.Ok().asText(req.headers.printHeaders))
```

### List of recent updates - Release: 0.2.1

01/10/23 - *h2c (plain text) http/2 support enabled, h2load gives: 175K TPS with 20 streams and 32 connections*
```scala
  //ssl context null will switch to h2c plain text
  exitCode <- new QuartzH2Server("localhost", 8443, 16000, null).startIO(R, filter, sync = false)
  
```

01/10/23 - *webfilter Request => IO[Option[Response]] added*

```scala
  val filter: WebFilter = (r: Request) =>
    IO(
      Option.when(r.uri.getPath().endsWith("na.txt"))(
        Response.Error(StatusCode.Forbidden).asText("Denied: " + r.uri.getPath())
      )
    )
    
    ...
    
    exitCode <- new QuartzH2Server("localhost", 8443, 16000, ctx).startIO(R, filter, sync = false)
```

01/05/23 - *http multipart support added*

```scala
  case req @ POST -> Root / "mpart" =>
      MultiPart.writeAll(req, HOME_DIR) *> IO(Response.Ok())
```

### How to run examples:<br>

```
 sbt IO/run
 or
 sbt RIO/run with ReaderT based routes.
 
 ```


### Performance test tool:
https://nghttp2.org/documentation/h2load-howto.html<br>
http2 spec compatibility tests:
https://formulae.brew.sh/formula/h2spec

* Now it's possible to switch TCP sockets between JAVA-NIO and Java Socket Api ( on blocking pool ).
Look at https://github.com/ollls/quartz-h2/blob/main/examples/IO/src/main/scala/Run.scala

```scala

  exitCode <- new QuartzH2Server("localhost", 8443, 60000, ctx).startIO( R, sync = false)
  
```


### How to run h2spec:

1. Start server with "sbt IO/run"<br>
2. ./h2spec http2 -h localhost -p 8443 -t -k<br>

You should get:<br>
```
Finished in 3.7611 seconds<br>
94 tests, 92 passed, 1 skipped, 1 failed<br>
```

### Use cases/example.
Please, refer to for use cases: quartz-h2/examples/
You can easily stream any big file in and out ( GET/POST) with fs2 stream. 

```scala

val R : HttpRouteIO = { 

    case GET -> Root => IO( Response.Ok().asText("OK")) 
    
    case GET -> Root / "example" =>
      //how to send data in separate H2 packets of various size. 
      val ts = Stream.emits( "Block1\n".getBytes())
      val ts2 = ts ++ Stream.emits( "Block22\n".getBytes())
      IO( Response.Ok().asStream( ts2 ) )

  }

  def run(args: List[String]): IO[ExitCode] =
    for {
      ctx <- QuartzH2Server.buildSSLContext("TLS", "keystore.jks", "password")
      exitCode <- new QuartzH2Server("localhost", 8443, 60000, ctx).startIO( R )

    } yield (exitCode)

}
```


Simple file retrieval.

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

HTTP Multipart.

```scala

    case req @ POST -> Root / "mpart" =>
      MultiPart.writeAll(req, HOME_DIR ) *> IO(Response.Ok())
      
```

Web site service.

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
  
  Upload example (POST https://localhost:8443/upload/op.jpeg).<br>
  mkdir /Users/user000/upload
  
  ```scala
  
   case req@POST -> Root / "upload" /  StringVar(_) => 
      for {
        reqPath <- IO( Path( "/Users/user000/" + req.uri.getPath() ))
        u <- req.stream.through( Files[IO].writeAll( reqPath ) ).compile.drain
      } yield( Response.Ok().asText("OK"))

  ```



