https://ollls.github.io/quartz-h2/index.html

<h1>Linux IoUring thru netio API for quartz-H2</h1>

[![Generic badge](https://img.shields.io/badge/quartz--h2-v0.9.0-blue)](https://repo1.maven.org/maven2/io/github/ollls/quartz-h2_3/0.9.0/)
[![Generic badge](https://img.shields.io/badge/Hello%20World-template-red)](https://github.com/ollls/json-template-qh2)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=ollls_quartz-h2&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=ollls_quartz-h2)<br>


https://ollls.github.io/quartz-h2/index.html

# Quick start

```
export LIBURING_PATH=~/Projects/CURRENT/liburing/
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

sbt IOURING/run
sbt -J-Xmx22G "IOURING/run --off"

h2load  -D8 -c32  https://localhost:8443/mov_bbb.mp4
h2load -D6 -c32 -m4 http://localhost:8443/mov_bbb.mp4

```

Some observations: 30% performance increase compared to NIO2. I assume that if you scale up (add more cores), the difference will increase. However, a regular 12-core CPU will show the most optimal results with 6 rings and 6 threads for a work-stealing pool. These are parameters for a stress test; under real circumstances, 4 rings will likely be a better option since you will experience less I/O and more backend compute.

Entry point IOApp for Linux IoUring, Optional sslContext: Some/None.<br>
https://github.com/ollls/quartz-h2/blob/dev4/examples/IOURING/src/main/scala/Run.scala
