# QuartzH2Server Startup Modes

This document outlines the different modes available for starting the QuartzH2Server in the Quartz H2 project.

## Available Modes

### 1. Full Sync Mode with Old Java Socket

- **Method**: `run1`
- **Description**: HTTP/2 TLS Service using synchronous mode with Java Sockets
- **TLS**: Required (only works with TLS)
- **Log Message**: "HTTP/2 TLS Service: QuartzH2 sync mode (sockets)"
- **Usage**: Called via `startSync` method or when `sync = true` in the `start` method

### 2. Async Mode with Netio (with TLS)

- **Method**: `run0`
- **Description**: HTTP/2 TLS Service using asynchronous mode with netio
- **TLS**: Required
- **Log Message**: "HTTP/2 TLS Service: QuartzH2 async mode (netio)"
- **Usage**: Called via `startAsync` method or when `sync = false` and TLS is enabled in the `start` method

### 3. Async Mode with Netio (without TLS - h2c)

- **Method**: `run3`
- **Description**: HTTP/2 h2c service using asynchronous mode with netio
- **TLS**: Not used (h2c - HTTP/2 cleartext)
- **Log Message**: "HTTP/2 h2c service: QuartzH2 async mode (netio)"
- **Usage**: Called via `startAsync` method when TLS is not enabled

### 4. IoUring Mode without TLS (Linux-specific)

- **Method**: `run4`
- **Description**: HTTP/2 h2c service using asynchronous mode with netio/linux iouring
- **TLS**: Not used (h2c)
- **Log Message**: "HTTP/2 h2c service: QuartzH2 async mode (netio/linux iouring)"
- **Usage**: Called via `startIoUring` method when TLS is not enabled

### 5. IoUring Mode with TLS (Linux-specific)

- **Method**: `run5`
- **Description**: HTTP/2 TLS service using asynchronous mode with netio/linux iouring
- **TLS**: Required
- **Log Message**: "HTTP/2 TLS service: QuartzH2 async mode (netio/linux iouring)"
- **Usage**: Called via `startIoUring` method when TLS is enabled

## Entry Points for Starting the Server

The server provides several methods to start the server with different configurations:

### `start(R: HttpRoute, sync: Boolean)`

General entry point that chooses between sync and async modes:
- If `sync = true`: Uses Java Sockets with TLS (run1)
- If `sync = false` with TLS: Uses async netio with TLS (run0)
- If `sync = false` without TLS: Uses async netio without TLS (run3)

### `startSync(R: HttpRoute, sync: Boolean, executor: ExecutorService, maxH2Streams: Int)`

- Specifically for sync mode with Java Sockets (run1)
- Requires TLS context
- Can be used with JDK on Virtual Threads with traditional sync (now virtual) Java Sockets

### `startAsync(R: HttpRoute, sync: Boolean, executor: ExecutorService, numOfCores: Int, maxH2Streams: Int)`

- For async mode with netio
- With TLS: Uses run0
- Without TLS: Uses run3
- Useful for running server on different thread pools, like FixedThreadPool, etc.

### `startIoUring(R: HttpRoute)`

- Linux-specific IO_uring implementation for high performance
- With TLS: Uses run5
- Without TLS: Uses run4
- Optimized for Linux systems with the io_uring interface for high-performance I/O operations

## Performance Notes

- IoUring modes are specifically optimized for Linux systems and can provide performance benefits
- According to code comments, IoUring with multiple rings (cores / 2) provides around 30% boost over NIO2
- The optimal setting for maximum H2 streams per connection is typically 2x the number of CPU cores
