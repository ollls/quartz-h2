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

1. Start server with "sbt IO/run"<br>
2. ./h2spec http2 -h localhost -p 8443 -t -k<br>

You should get:<br>
Finished in 3.7611 seconds<br>
94 tests, 92 passed, 1 skipped, 1 failed<br>


h2load -D10  -c33 -m20 https://localhost:8443


finished in 10.00s, 9650.20 req/s, 198.08KB/s
requests: 96502 total, 97162 started, 96502 done, 96502 succeeded, 0 failed, 0 errored, 0 timeout
status codes: 96502 2xx, 0 3xx, 0 4xx, 0 5xx
traffic: 1.93MB (2028324) total, 94.24KB (96502) headers (space savings 90.00%), 188.48KB (193004) data
                     min         max         mean         sd        +/- sd
time for request:      441us    148.17ms     67.35ms     16.30ms    68.20%
time for connect:    92.07ms    235.75ms    113.97ms     37.18ms    90.91%
time to 1st byte:   104.51ms    344.04ms    179.90ms     63.82ms    66.67%
req/s           :     283.96      303.56      292.41        4.05    78.79%

