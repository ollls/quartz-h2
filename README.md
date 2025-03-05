<h1>Working PoC</h1>

Linux IoUring with modded lib from https://github.com/bbeaupain/nio_uring

To build:
You will need: https://github.com/axboe/liburing.git

```
export LIBURING_PATH=~/Projects/CURRENT/liburing/
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
sbt IOURING/run  ( only plain text http/1 and h2c at this moment)
```

h2load  -n48 -c12  https://localhost:8443/ldt

Some observations: I found nothing earth-shattering regarding iouring performance on Linux. With fine tuning, you can achieve approximately a 15% performance increase compared to NIO2. I assume that if you scale up (add more cores), the difference will increase. However, a regular 12-core CPU will show the most optimal results with 6 rings and 6 threads for a work-stealing pool. These are parameters for a stress test; under real circumstances, 4 rings will likely be a better option since you will experience less I/O and more backend compute.
