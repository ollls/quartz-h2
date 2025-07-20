# QuartzH2Server - HTTP/2 Server Implementation

## Overview

QuartzH2Server is a high-performance HTTP/2 server implemented in Scala using the Cats Effect library. It provides support for both secure (TLS) and non-secure connections, with capabilities for HTTP/1.1 to HTTP/2 protocol upgrades (h2c).

![HTTP/2 Protocol](https://http2.github.io/asset/http2.svg)

## Key Components

### Object QuartzH2Server

- Contains utility methods like `setLoggingLevel` and `buildSSLContext`
- Provides helper functions for server configuration

### Class QuartzH2Server

The main server implementation that handles HTTP/2 connections with the following parameters:

| Parameter | Description |
|-----------|-------------|
| `HOST` | Server host address |
| `PORT` | Server port number |
| `h2IdleTimeOutMs` | Connection idle timeout |
| `sslCtx` | Optional SSL context for secure connections |
| `incomingWinSize` | Initial window size for flow control |
| `onConnect`/`onDisconnect` | Callback functions for connection events |

## Connection Handling

- **`doConnect`**: Handles new connections, detects HTTP/2 preface
- **`doConnectUpgrade`**: Manages HTTP/1.1 to HTTP/2 protocol upgrades
- Error handling via `errorHandler` method

## Server Modes

The server supports multiple operational modes:

### 1. Synchronous Mode (`run1`)
- Uses traditional Java sockets
- Requires TLS context
- Suitable for virtual threads (Project Loom)

```scala
run1(R, h2streams, h2IdleTimeOutMs)
```

### 2. Asynchronous Mode (`run0`, `run3`)
- Uses Java NIO AsynchronousChannelGroup
- `run0`: For TLS connections
- `run3`: For non-secure (h2c) connections

```scala
run0(e, R, cores, h2streams, h2IdleTimeOutMs).evalOn(ec)
```

### 3. IO_uring Mode (`run4`)
- Linux-specific high-performance I/O
- Uses the `io.quartz.iouring` package
- Optimized for high concurrency

```scala
run4(e, R, cores, h2streams, h2IdleTimeOutMs).evalOn(ec)
```

## HTTP Protocol Support

- **Full HTTP/2 support** with stream multiplexing
- **HTTP/1.1 fallback** with upgrade capability to HTTP/2
- Header parsing and protocol detection

## Public API

| Method | Description |
|--------|-------------|
| `startIO` | Starts server with IO-based routes |
| `startRIO` | Starts server with Reader IO-based routes |
| `startAsync` | Configurable async server with executor service |
| `startSync` | Synchronous server mode |
| `startIoUring` | Linux-specific IO_uring-based server |

## Key Features

1. **Multiplexing**: Supports multiple concurrent streams per connection
2. **Protocol Negotiation**: ALPN for HTTP/2 and fallback to HTTP/1.1
3. **Flow Control**: Implements HTTP/2 flow control with configurable window sizes
4. **Resource Management**: Proper cleanup of connections and resources
5. **Configurability**: Flexible thread pool and concurrency settings

## Technical Implementation

- Uses **Cats Effect** for pure functional programming
- Leverages **fs2** for streaming operations
- Implements HTTP/2 framing and settings management
- Supports both TLS and plain text connections
- Uses `io.quartz.iouring` for IO_uring support on Linux systems

## Code Example

```scala
// Starting an HTTP/2 server with a simple route
val server = new QuartzH2Server(
  HOST = "localhost",
  PORT = 8080,
  h2IdleTimeOutMs = 30000,
  sslCtx = None
)

val route: HttpRouteIO = {
  case req if req.path == "/" =>
    IO.pure(Some(Response(200, Headers("content-type" -> "text/plain"), "Hello, HTTP/2!")))
  case _ =>
    IO.pure(Some(Response(404, Headers.empty, "Not Found")))
}

server.startIO(route, sync = false)
```

## Architecture Diagram

```
┌───────────────┐      ┌───────────────┐      ┌───────────────┐
│  HTTP Client  │◄────►│  QuartzH2     │◄────►│  Application  │
│               │      │  Server       │      │  Logic        │
└───────────────┘      └───────────────┘      └───────────────┘
                              │
                              ▼
                       ┌─────────────┐
                       │  Connection │
                       │  Handling   │
                       └─────────────┘
                              │
                       ┌──────┴──────┐
                ┌──────┤  Protocol   ├──────┐
                │      │  Detection  │      │
                ▼      └─────────────┘      ▼
        ┌───────────┐              ┌───────────────┐
        │  HTTP/1.1 │              │    HTTP/2     │
        └───────────┘              └───────────────┘
                │                          │
                ▼                          ▼
        ┌───────────┐              ┌───────────────┐
        │  Upgrade  │──────────────►  Multiplexed  │
        │  to h2c   │              │   Streams     │
        └───────────┘              └───────────────┘
```

## Performance Considerations

The server is designed to be highly concurrent and efficient, with optimizations for different deployment scenarios:

- **Thread Pool Configuration**: Adjustable based on workload
- **Stream Multiplexing**: Configurable maximum streams per connection
- **IO_uring**: Leverages Linux kernel's high-performance I/O interface
- **Flow Control**: Prevents resource exhaustion

## Dependencies

- Cats Effect
- fs2
- log4cats
- io.quartz.iouring (JNI wrapper for Linux io_uring)
