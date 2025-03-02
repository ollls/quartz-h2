<h1>Working PoC</h1>

Linux IoUring with modded lib from https://github.com/bbeaupain/nio_uring

To build:
You will need: https://github.com/axboe/liburing.git

export LIBURING_PATH=~/Projects/CURRENT/liburing/
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

 sbt IOURING/run  ( only plain text http/1 and h2c at this moment)
