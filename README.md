 how to run examples:
 sbt publishLocal<br>
 sbt IO/run<br>
 sbt RIO/run<br>


test: h2load -t1 -D10 -c4 -m10 https://localhost:8443/hello

performance test tool:
https://nghttp2.org/documentation/h2load-howto.html<br>
http2 spec compatibility tests:
https://formulae.brew.sh/formula/h2spec


100% asyncronous Java NIO based implementation of http/2 packet streaming with TLS emcryption implemented as scala CATS effect.
Solution was tested and optimized to produce highest possible TPS.
It uses single java.util.concurrent.ForkJoinPool for JAVA NIO Socket Groups and for evalOn() with CATS Effects.

Http/2 weights and dependecy are not implemented, for performance reasons. 
Goal was to reach the highest possible throughtput with 10-20 multiple highly paralel http/2 streams relying on excelent CATS Effect fiber manager.

How to run h2spec:

1. Start server with "sbtIO/run"<br>
2. ./h2spec http2 -h localhost -p 8443 -t -k<br>

You should get:<br>
Finished in 3.7611 seconds<br>
94 tests, 92 passed, 1 skipped, 1 failed<br>

