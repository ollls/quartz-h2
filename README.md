
[![Generic badge](https://img.shields.io/badge/quartz--h2-v0.2.0-blue)](https://repo1.maven.org/maven2/io/github/ollls/quartz-h2_3/0.2.0)
```
"io.github.ollls" %% "quartz-h2" % "0.2.0"
```
01/05/23 - http multipart support added.

100% asyncronous Java NIO based implementation of http/2 packet streaming server with TLS encryption implemented as scala CATS effect.
Direct native translation of fs2 stream chunks into http2 packets and vice versa, packets to fs2 chunks (inbound and outbound).<br>

* Tested and optimized to produce highest possible TPS. <br><br>( **120K TPS** on MacBook, see details below )<br><br>
It uses single java.util.concurrent.ForkJoinPool for JAVA NIO Socket Groups and for evalOn() with CATS Effects.
Http/2 weights and dependecy are not implemented, for performance reasons. 
Goal was to reach the highest possible throughtput with 10-20 multiple highly paralel http/2 streams relying on excelent CATS Effect fiber manager.

>**Recommended JSON parser: https://github.com/plokhotnyuk/jsoniter-scala**<br>
example project: https://github.com/ollls/json-template-qh2<br>

 * Use cases:
 https://github.com/ollls/quartz-h2/blob/main/examples/IO/src/main/scala/Run.scala
 
 * to change debug level: edit logback-test.xml<br>
 
 * how to run examples:<br>

```
 sbt IO/run
 or
 sbt RIO/run with ReaderT based routes.
 
 ```


* performance test tool:
https://nghttp2.org/documentation/h2load-howto.html<br>
http2 spec compatibility tests:
https://formulae.brew.sh/formula/h2spec

* Now it's possible to switch TCP sockets between JAVA-NIO and Java Socket Api ( on blocking pool ).
Look at https://github.com/ollls/quartz-h2/blob/main/examples/IO/src/main/scala/Run.scala

```scala

  exitCode <- new QuartzH2Server("localhost", 8443, 60000, ctx).startIO( R, sync = false)
  
```


How to run h2spec:

1. Start server with "sbt IO/run"<br>
2. ./h2spec http2 -h localhost -p 8443 -t -k<br>

You should get:<br>
```
Finished in 3.7611 seconds<br>
94 tests, 92 passed, 1 skipped, 1 failed<br>
```

MacBook Pro (16-inch, 2019)<br>
h2load  -D10 -c32 -t4 -m20 https://localhost:8443/test

```
finished in 10.00s, 121067.50 req/s, 1.15MB/s
requests: 1210675 total, 1211315 started, 1210675 done, 1210675 succeeded, 0 failed, 0 errored, 0 timeout
status codes: 1210675 2xx, 0 3xx, 0 4xx, 0 5xx
traffic: 11.55MB (12108478) total, 1.15MB (1210675) headers (space savings 90.00%), 0B (0) data
                     min         max         mean         sd        +/- sd
time for request:      254us     35.76ms      5.15ms      1.96ms    82.35%
time for connect:    42.95ms    116.00ms     74.39ms     29.24ms    43.75%
time to 1st byte:    44.37ms    138.99ms     79.64ms     32.58ms    50.00%
req/s           :    3729.09     3845.64     3783.19       25.26    75.00%
```

Please, refer to for use cases.
quartz-h2/examples/
Route DSL is the same as for zio-tls-http, <br>
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



