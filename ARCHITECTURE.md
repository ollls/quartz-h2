# Quartz-H2 Server Architecture

This document describes the architecture of the Quartz-H2 HTTP/2 server, with a focus on its key components: threads, fibers, queues, and fs2 streams.

## High-Level Architecture

```
                                 ┌─────────────────────────────────────┐
                                 │           QuartzH2Server            │
                                 │                                     │
                                 │  ┌─────────────────┐ ┌─────────────┐ │
                                 │  │   TLS Context   │ │HTTP Routes│ │
                                 │  └─────────────────┘ └─────────────┘ │
                                 └───────────────┬─────────────────────┘
                                                 │
                                                 ▼
┌─────────────────┐           ┌─────────────────────────────────────┐
│ Incoming        │           │         ServerSocketLoop             │
│ Client Requests ├──────────►│ (Accepts connections & starts       │
└─────────────────┘           │  Http2Connection for each client)    │
                              └───────────────┬─────────────────────┘
                                              │
                                              ▼
                              ┌─────────────────────────────────────┐
                              │         Http2Connection             │
                              │                                     │
                              │  ┌─────────────┐  ┌──────────────┐  │
                              │  │ Connection  │  │ Stream       │  │
                              │  │ Settings    │  │ Management   │  │
                              │  └─────────────┘  └──────────────┘  │
                              └──┬─────────────────────────┬────────┘
                                 │                         │
                                 ▼                         │
┌────────────────────────────────┐                         │
│      Inbound Processing        │                         │
│                                │                         │
│ 1. Read socket data            │                         │
│ 2. makePacketStream            │                         │
│    - Chunk raw bytes           │                         │
│    - Extract HTTP/2 frames     │                         │
│ 3. packet_handler              │                         │
│    - Parse frame headers       │                         │
│    - Route to frame handlers   │                         │
└────────────┬─────────────────┬─┘                         │
             │                 │                           │
             ▼                 ▼                           ▼
┌────────────────────┐ ┌────────────────────┐  ┌────────────────────────────────┐
│ Connection Frames  │ │  Stream Frames     │  │       Stream Processing        │
│ (SETTINGS, PING)   │ │ (HEADERS, DATA)    │  │                                │
│                    │ │                    │  │ - Create stream objects        │
│ - Update settings  │ │ - Accumulate data  │  │ - Map requests to routes       │
│ - Flow control     │ │ - Update windows   │  │ - Process request body         │
│ - Connection mgmt  │ │ - Trigger streams  │  │ - Generate responses           │
└────────────────────┘ └──────────┬─────────┘  └───────────────┬────────────────┘
                                   │                            │
                                   └────────────┬──────────────┘
                                                │
                                                ▼
                                   ┌────────────────────────────┐
                                   │     fs2 Stream Processing  │
                                   │                            │
                                   │ - Chunked body handling    │
                                   │ - Async request processing │
                                   │ - Backpressure management  │
                                   └────────────────────────────┘
```

## Core Components

### 1. Threads & Fibers

Quartz-H2 uses a fiber-based concurrency model powered by cats-effect's `IO` monad:

- **Main Thread**: Runs the server socket loop that accepts incoming connections
- **Fiber Management**:
  - Each `Http2Connection` spawns multiple fibers to handle different aspects of connection processing
  - The `outBoundWorkerProc` fiber handles all outbound packet transmission
  - Stream processing fibers handle individual HTTP/2 streams concurrently
  - Route handler fibers process HTTP requests and generate responses

```scala
// Example of fiber creation for outbound packet processing
runMe2 = outBoundWorkerProc(ch, outq, shutdownPromise)
  .handleErrorWith(e => Logger[IO].debug("outBoundWorkerProc fiber: " + e.toString()))
  .iterateUntil(_ == true)
  .start
```

```scala
// Example of stream processing fiber creation
streamFork = for {
  h <- d.get
  _ <- interceptContentLen(c, h)
  r <- IO(Request(...))
  _ <- route2(streamId, r)
} yield ()
_ <- streamFork.handleErrorWith(e => handleStreamErrors(streamId, e)).start
```

### 2. Queues

Queues are a critical component for managing data flow between different parts of the system:

- **Outbound Queue (`outq`)**: Bounded queue (1024 capacity) for outgoing packets
- **Stream Data Queues (`inDataQ`)**: Unbounded queues for accumulating data packets for each stream
- **Flow Control Queues (`outXFlowSync`)**: Synchronization queues for HTTP/2 flow control
- **Window Update Queues (`syncUpdateQ`)**: Queues for coordinating window updates

```scala
// Queue initialization examples
outq <- Queue.bounded[IO, ByteBuffer](1024)  // Outbound packet queue
dataIn <- Queue.unbounded[IO, ByteBuffer]   // Stream data queue
xFlowSync <- Queue.unbounded[IO, Boolean]   // Flow control sync queue
updSyncQ <- Queue.dropping[IO, Unit](1)    // Window update queue
```

### 3. fs2 Streams

The server uses fs2 streams extensively for processing data in a functional, composable way:

- **Packet Stream Processing**: Transforms raw socket data into HTTP/2 frames
- **Request Body Processing**: Handles chunked request bodies with proper backpressure
- **Response Generation**: Streams response data with flow control

```scala
// Packet stream creation from socket
def makePacketStream(ch: IOChannel, keepAliveMs: Int, leftOver: Chunk[Byte]): Stream[IO, Chunk[Byte]] = {
  val s0 = Stream.chunk[IO, Byte](leftOver)
  val s1 = Stream.repeatEval(ch.read(keepAliveMs)).flatMap(c0 => Stream.chunk(c0))
  // Processing logic with Pull-based stream transformation
  // ...
}
```

```scala
// Data stream creation for request bodies
def makeDataStream(c: Http2ConnectionCommon, q: Queue[IO, ByteBuffer]) = {
  val dataStream0 = Stream.eval(dataEvalEffectProducer(c, q)).repeat.takeThrough { buffer =>
    // Process frames until END_STREAM flag
    // ...
  }
  dataStream0.flatMap(b => Stream.emits(ByteBuffer.allocate(b.remaining).put(b).array()))
}
```

## Inbound Flow and Packet Parsing

The inbound data flow in Quartz-H2 follows a sophisticated pipeline that transforms raw socket data into HTTP/2 frames and routes them to the appropriate streams:

### 1. Socket Reading and Initial Processing

1. The `processIncoming` method initiates the inbound flow, starting with any leftover bytes from previous reads:

```scala
def processIncoming(leftOver: Chunk[Byte]): IO[Unit] = (for {
  _ <- Logger[IO].trace(s"Http2Connection.processIncoming() leftOver= ${leftOver.size}")
  _ <- Http2Connection
    .makePacketStream(ch, HTTP2_KEEP_ALIVE_MS, leftOver)
    .foreach(packet => { packet_handler(httpReq11, packet) })
    .compile
    .drain
} yield ()).handleErrorWith[Unit] {
  // Error handling logic
}
```

2. The `makePacketStream` method transforms raw socket data into properly chunked HTTP/2 frames:

```scala
def makePacketStream(ch: IOChannel, keepAliveMs: Int, leftOver: Chunk[Byte]): Stream[IO, Chunk[Byte]] = {
  val s0 = Stream.chunk[IO, Byte](leftOver)  // Start with leftover bytes
  val s1 = Stream
    .repeatEval(ch.read(keepAliveMs))        // Continuously read from socket
    .flatMap(c0 => Stream.chunk(c0))         // Convert to stream of bytes

  // Pull-based stream transformation logic to extract complete HTTP/2 frames
  // Uses a recursive algorithm to handle frame boundaries correctly
}
```

### 2. Frame Parsing and Routing

Once the raw bytes are chunked into HTTP/2 frames, they are processed by the `packet_handler` method:

```scala
private[this] def packet_handler(http11request: Ref[IO, Option[Request]], packet: Chunk[Byte]): IO[Unit] = {
  val buffer = packet.toByteBuffer
  val packet0 = buffer.slice // preserve reference to whole packet

  // Extract frame metadata
  val len = Frames.getLengthField(buffer)      // Frame length
  val frameType = buffer.get()                 // Frame type (HEADERS, DATA, etc.)
  val flags = buffer.get()                     // Frame flags
  val streamId = Frames.getStreamId(buffer)    // Stream ID
  
  // Validate frame and route to appropriate handler based on frame type
  // ...
}
```

3. The frame is then routed to a specific handler based on its type:

- **Connection-level frames** (SETTINGS, PING, GOAWAY): Processed at the connection level
- **Stream-level frames** (HEADERS, DATA, CONTINUATION): Routed to the appropriate stream

### 3. Stream-Level Processing

For stream-level frames, the processing depends on the frame type:

- **HEADERS frames**: Create new streams or process trailing headers
- **DATA frames**: Accumulate request body data
- **CONTINUATION frames**: Continue processing fragmented headers

## Stream Management

Stream management is a critical aspect of HTTP/2 handling, as it enables multiplexing multiple requests over a single connection:

### 1. Stream Creation and Lifecycle

1. **Stream Creation**: When a HEADERS frame arrives for a new stream ID, the `openStream` method is called:

```scala
private[this] def openStream(streamId: Int, flags: Int) = for {
  // Check concurrent stream limits
  nS <- IO(concurrentStreams.get)
  _ <- Logger[IO].debug(s"Open stream: $streamId  total = ${streamTbl.size} active = ${nS}")
  
  // Create synchronization primitives for the stream
  d <- Deferred[IO, Headers]                  // For headers completion
  trailingHdr <- Deferred[IO, Headers]        // For trailing headers
  contentLenFromHeader <- Deferred[IO, Option[Int]]
  
  // Create data structures for the stream
  header <- IO(ArrayBuffer.empty[ByteBuffer])  // For header frames
  trailing_header <- IO(ArrayBuffer.empty[ByteBuffer])
  
  // Create queues and refs for flow control
  xFlowSync <- Queue.unbounded[IO, Boolean]    // Flow control sync
  dataIn <- Queue.unbounded[IO, ByteBuffer]    // Data frame queue
  transmitWindow <- Ref[IO].of[Long](settings_client.INITIAL_WINDOW_SIZE)
  localInboundWindowSize <- Ref[IO].of[Long](INITIAL_WINDOW_SIZE)
  
  // Create and register the stream object
  c <- IO(Http2Stream(...))
  _ <- IO(concurrentStreams.incrementAndGet())
  _ <- IO(this.streamTbl.put(streamId, c))
  
  // Start a fiber to process the stream
  streamFork = for {
    h <- d.get                                // Wait for headers to be complete
    _ <- interceptContentLen(c, h)           // Extract content length if present
    r <- IO(Request(...))                     // Create request object
    _ <- route2(streamId, r)                 // Route the request
  } yield ()
  _ <- streamFork.handleErrorWith(e => handleStreamErrors(streamId, e)).start
} yield ()
```

2. **Stream State Management**: Each stream progresses through several states:
   - **Idle**: Before the stream is created
   - **Open**: After HEADERS frame is received
   - **Half-closed (local/remote)**: After END_STREAM flag is received in one direction
   - **Closed**: After both directions have END_STREAM or after RST_STREAM

```scala
// Stream state flags
var endFlag = false        // half-closed if true
var endHeadersFlag = false // headers completed if true
```

### 2. Data Accumulation and Processing

1. **Header Accumulation**: HEADERS and CONTINUATION frames are accumulated in buffers:

```scala
private[this] def accumHeaders(streamId: Int, bb: ByteBuffer): IO[Unit] =
  updateStreamWith(2, streamId, c => IO(c.header.addOne(bb)))
```

2. **Data Accumulation**: DATA frames are queued for processing:

```scala
private[this] def accumData(streamId: Int, bb: ByteBuffer, dataSize: Int): IO[Unit] = {
  for {
    o_c <- IO(this.streamTbl.get(streamId))
    _ <- IO.raiseError(ErrorGen(streamId, Error.FRAME_SIZE_ERROR, "invalid stream id")).whenA(o_c.isEmpty)
    c <- IO(o_c.get)
    _ <- IO(c.contentLenFromDataFrames += dataSize)

    _ <- this.incrementGlobalPendingInboundData(dataSize)
    _ <- c.bytesOfPendingInboundData.update(_ + dataSize)

    _ <- c.inDataQ.offer(bb)  // Queue the data frame for processing
  } yield ()
}
```

3. **Stream Triggering**: Once headers are complete, the stream is triggered for processing:

```scala
private[this] def triggerStream(streamId: Int): IO[Unit] = {
  updateStreamWith(
    5,
    streamId,
    c =>
      for {
        headers <- IO(headerDecoder.decodeHeaders(c.header.toSeq))
        _ <- c.d.complete(headers).void  // Complete the headers deferred
      } yield ()
  )
}
```

### 3. Request Processing and Routing

Once a stream is triggered, the request is processed and routed to the appropriate handler:

```scala
private[this] def route2(streamId: Int, request: Request): IO[Unit] = {
  val T = for {
    // Validate request headers
    _ <- Logger[IO].debug(s"Processing request for stream = $streamId ${request.method.name} ${request.path} ")
    
    // Route the request to the user-provided handler
    response_o <- (httpRoute(request)).handleErrorWith {
      // Error handling
    }
    
    // Process the response
    _ <- response_o match {
      case Some(response) => {
        // Send response headers
        // Stream response body
        // Send trailing headers if present
      }
      case None => {
        // Send 404 response
      }
    }
    
    // Close the stream
    _ <- closeStream(streamId)
  } yield ()
  T
}
```

### 4. Stream Cleanup

After a stream is complete, it's cleaned up to free resources:

```scala
private[this] def closeStream(streamId: Int): IO[Unit] = {
  for {
    _ <- IO(concurrentStreams.decrementAndGet())
    _ <- IO(streamTbl.remove(streamId))
    _ <- Logger[IO].debug(s"Close stream: $streamId")
  } yield ()
}
```

## Request Handling

1. HEADERS frames are decoded into HTTP request metadata
2. Request bodies are processed as DATA frames via fs2 streams
3. Requests are matched against configured HTTP routes
4. Route handlers process requests and generate responses

```scala
private[this] def route2(streamId: Int, request: Request): IO[Unit] = {
  // Process request headers
  // Match against routes
  // Generate and send response
  // ...
}
```

## Response Delivery

1. Response headers are encoded and sent as HEADERS frames
2. Response bodies are streamed as DATA frames with proper flow control
3. Trailing headers are sent if needed
4. Stream is closed after response completion

## Flow Control Mechanism

HTTP/2 flow control is implemented at both connection and stream levels:

1. **Window Size Tracking**:
   - Global connection window (`globalTransmitWindow`, `globalInboundWindow`)
   - Per-stream windows (`transmitWindow`, `inboundWindow`)

2. **Window Updates**:
   - Automatic window updates when buffer space is consumed
   - Updates sent when available window falls below threshold

```scala
private[this] def windowsUpdate(
    c: Http2ConnectionCommon,
    streamId: Int,
    received: Ref[IO, Long],
    window: Ref[IO, Long],
    len: Int
) =
  for {
    bytes_received <- received.getAndUpdate(_ - len)
    bytes_available <- window.getAndUpdate(_ - len)
    send_update <- IO(
      bytes_received < c.INITIAL_WINDOW_SIZE * 0.7 && bytes_available < c.INITIAL_WINDOW_SIZE * 0.3
    )
    upd = c.INITIAL_WINDOW_SIZE - bytes_available.toInt
    _ <- (c.sendFrame(Frames.mkWindowUpdateFrame(streamId, upd)) *> window
      .update(_ + upd)).whenA(send_update)
  } yield (send_update)
```

## Error Handling

1. **Protocol Errors**:
   - RST_STREAM frames for stream-specific errors
   - GOAWAY frames for connection-level errors

2. **Application Errors**:
   - HTTP status codes for application-level errors
   - Error responses with appropriate headers

3. **Resource Management**:
   - Proper cleanup of streams and connections
   - Graceful shutdown ensuring in-flight requests complete

## Conclusion

The Quartz-H2 server architecture demonstrates a modern, functional approach to HTTP/2 server implementation using cats-effect and fs2. The use of fibers instead of threads, functional queues for coordination, and fs2 streams for data processing creates a highly concurrent, resource-efficient server capable of handling many simultaneous connections with minimal overhead.
