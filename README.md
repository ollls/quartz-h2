 how to run examples:
 sbt publishLocal<br>
 sbt IO/run<br>
 sbt RIO/run<br>


test: h2load -t1 -D10 -c4 -m10 https://localhost:8443/health

test tool:
https://nghttp2.org/documentation/h2load-howto.html
