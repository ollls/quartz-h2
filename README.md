# Asynchronous Java NIO http/2 TLS packet streaming server/client.

TLS encryption implemented as scala CATS effects with ALPN h2 tag. Direct native translation of fs2 stream chunks into http2 packets, where http Request's data and http Response's data mapped directy to fs2 streams. Tested and optimized to produce highest possible TPS.(120K TPS on MacBook with h2load tool, see details below). Single java.util.concurrent.ForkJoinPool for JAVA NIO Socket Groups and for evalOn() with CATS Effects. Http/2 weights and dependency are not implemented, for performance reasons.

https://ollls.github.io/quartz-h2/index.html

```
"io.github.ollls" %% "tapir-quartz-h2" % "0.9.0"
```

https://ollls.github.io/quartz-h2/index.html

[![Generic badge](https://img.shields.io/badge/quartz--h2-v0.9.0-blue)](https://repo1.maven.org/maven2/io/github/ollls/quartz-h2_3/0.9.0/)
[![Generic badge](https://img.shields.io/badge/Hello%20World-template-red)](https://github.com/ollls/json-template-qh2)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=ollls_quartz-h2&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=ollls_quartz-h2)<br>

## Examples, use cases:
https://github.com/ollls/quartz-h2/tree/main/examples


## Quick start
Running server with git clone on the code base.

```
git clone https://github.com/ollls/quartz-h2.git
sbt IO/run
sbt TAPIR/run
sbt RIO/run
```

- Open https://localhost:8443/doc/index.html in the browser.
- Open https://localhost:8443/mp4safari - to test iPhone/Safari compatible ranged http video streams with TAPIR/run)

- Partial func based route: https://github.com/ollls/quartz-h2/blob/main/examples/IO/src/main/scala/Run.scala<br>
- Tapir endpoints routes:   https://github.com/ollls/quartz-h2/blob/main/examples/STTP/src/main/scala/Run.scala<br>


- https://github.com/ollls/json-template-qh2
- https://github.com/ollls/qh2_tapir_template
- https://github.com/ollls/quartz-h2-gptapi
- https://github.com/ollls/qh2_grpc

## Tests

### Tests with h2load
https://nghttp2.org/documentation/h2load-howto.html 

### http2 spec compatibility tests
https://formulae.brew.sh/formula/h2spec

```
h2load -D10 -c62 -m30 -t2 https://localhost:8443/test

finished in 10.01s, 111220.90 req/s, 1.06MB/s
requests: 1112209 total, 1114069 started, 1112209 done, 1112209 succeeded, 0 failed, 0 errored, 0 timeout
status codes: 1112209 2xx, 0 3xx, 0 4xx, 0 5xx
traffic: 10.61MB (11125438) total, 1.06MB (1112209) headers (space savings 90.00%), 0B (0) data
                     min         max         mean         sd        +/- sd
time for request:      200us     97.10ms     12.79ms      5.35ms    73.18%
time for connect:    43.33ms    170.60ms    105.62ms     51.84ms    38.71%
time to 1st byte:    55.31ms    181.53ms    120.53ms     55.00ms    51.61%
req/s           :    1762.79     1837.34     1793.33       18.37    69.35%
```

### How to run h2spec:

Start server with "sbt IO/run" 
```
./h2spec http2 -h localhost -p 8443 -t -k You should get:

Finished in 1.4891 seconds
94 tests, 93 passed, 1 skipped, 0 failed
```

### How to run test cases:

sbt test

