# Asynchronous Java NIO http/2 TLS packet streaming server/client.
[![Generic badge](https://img.shields.io/badge/quartz--h2-v0.9.0-blue)](https://repo1.maven.org/maven2/io/github/ollls/quartz-h2_3/0.9.0/)
[![Generic badge](https://img.shields.io/badge/Hello%20World-template-red)](https://github.com/ollls/json-template-qh2)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=ollls_quartz-h2&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=ollls_quartz-h2)<br>

TLS encryption implemented as scala CATS effects with ALPN h2 tag. Direct native translation of fs2 stream chunks into http2 packets, where http Request's data and http Response's data mapped directy to fs2 streams. Tested and optimized to produce highest possible TPS.(120K TPS on MacBook with h2load tool, see details below). Single java.util.concurrent.ForkJoinPool for JAVA NIO Socket Groups and for evalOn() with CATS Effects. Http/2 weights and dependency are not implemented, for performance reasons.

## Documentation
https://ollls.github.io/quartz-h2/index.html<br>
https://github.com/ollls/json-template-qh2<br>

ZIO Variant: https://github.com/ollls/zio-quartz-h2

## 0.8.5 README
https://github.com/ollls/quartz-h2/blob/v0.8.5/README.md

## Examples, use cases
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

### Tests with h2load (AMD Ryzen 9 9950X)
https://nghttp2.org/documentation/h2load-howto.html 

### http2 spec compatibility tests
https://formulae.brew.sh/formula/h2spec

```
h2load -D10 -c62 -m30 -t2 https://localhost:8443/test

finished in 10.01s, 181570.00 req/s, 1.73MB/s
requests: 1815700 total, 1817684 started, 1815700 done, 1815700 succeeded, 0 failed, 0 errored, 0 timeout
status codes: 1815700 2xx, 0 3xx, 0 4xx, 0 5xx
traffic: 17.32MB (18160348) total, 1.73MB (1815700) headers (space savings 90.00%), 0B (0) data
                     min         max         mean         sd        +/- sd
time for request:       94us    159.39ms     10.63ms     13.72ms    89.47%
time for connect:    10.31ms       2.04s    261.10ms    605.82ms    85.48%
time to 1st byte:    54.64ms       2.14s    305.98ms    612.49ms    85.48%
req/s           :    2353.88     3028.57     2928.14      194.00    85.48%

```

IOURING with 4 rings on (AMD Ryzen 9 9950X) 
( different dynamic more connections without degradation, on big multi-core it's important to have number of IOURING rings > 2 ) 
```
h2load -D10 -c140 -m20 -t1 https://localhost:8443/test

finished in 10.02s, 172321.00 req/s, 1.64MB/s
requests: 1723210 total, 1726010 started, 1723210 done, 1723210 succeeded, 0 failed, 0 errored, 0 timeout
status codes: 1723210 2xx, 0 3xx, 0 4xx, 0 5xx
traffic: 16.44MB (17239660) total, 1.64MB (1723210) headers (space savings 90.00%), 0B (0) data
                     min         max         mean         sd        +/- sd
time for request:       51us     89.03ms     14.92ms     10.84ms    82.75%
time for connect:     7.56ms       1.52s    635.12ms    436.57ms    59.29%
time to 1st byte:     9.16ms       1.53s    642.55ms    440.41ms    58.57%
req/s           :    1056.13     1560.53     1228.97      128.21    67.14%

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

