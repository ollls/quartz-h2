# HTTP/2 Flow Control in Quartz-H2

This document explains how HTTP/2 flow control is implemented in the Quartz-H2 server, focusing on the mechanisms used to prevent senders from overwhelming receivers with data.

## HTTP/2 Flow Control Fundamentals

HTTP/2 flow control is a mechanism that prevents senders from overwhelming receivers with data. It operates at two levels:

1. **Connection-level flow control**: Applies to the entire connection
2. **Stream-level flow control**: Applies to individual streams

Flow control in HTTP/2 is credit-based, meaning:

- Each receiver advertises an initial window size (default 65,535 bytes)
- As data is sent, this window is consumed
- Receivers must explicitly increase the window by sending WINDOW_UPDATE frames
- If the window reaches zero, the sender must stop sending data until the window is increased

## Flow Control Implementation in Quartz-H2

Quartz-H2 implements HTTP/2 flow control using a combination of reference counters, queues, and functional reactive patterns. The key components are:

### 1. Window Management

```scala
// Flow control windows are tracked using Ref[IO, Long] for atomic updates
val inboundWindow: Ref[IO, Long]     // Tracks available receive window
val transmitWindow: Ref[IO, Long]    // Tracks available send window
val bytesOfPendingInboundData: Ref[IO, Long]  // Tracks received bytes
```

These reference counters are maintained at both the connection level (global) and the stream level.

### 2. Synchronization Queues

Quartz-H2 uses two specialized queues to coordinate flow control operations in a non-blocking, reactive manner:

```scala
// Flow control synchronization queues
xFlowSync <- Queue.unbounded[IO, Boolean]   // Flow control sync queue
updSyncQ <- Queue.dropping[IO, Unit](1)    // Window update queue
```

#### The `xFlowSync` Queue

The `xFlowSync` queue is a critical component that implements backpressure in the HTTP/2 flow control system. It's an unbounded queue of boolean values that serves as a signaling mechanism between window updates and data transmission.

**Key characteristics:**

1. **Reactive Signaling**: When a WINDOW_UPDATE frame is received, a `true` value is offered to the queue, signaling that more data can be sent:

```scala
// In updateWindowStream method
_ <- stream.outXFlowSync.offer(true)
```

2. **Blocking Mechanism**: Before sending data, the transmitter waits on the queue to ensure sufficient window is available:

```scala
// In txWindow_Transmit method
b <- stream.outXFlowSync.take
_ <- IO.raiseError(QH2InterruptException()).whenA(b == false)
```

3. **Cancellation Support**: A `false` value can be offered to the queue to signal cancellation, which causes the transmitter to raise a `QH2InterruptException`:

```scala
// Example from client implementation
_ <- streams.traverse(s0 => s0.outXFlowSync.offer(false) *> s0.outXFlowSync.offer(false))
```

4. **Per-Stream Control**: Each HTTP/2 stream has its own `xFlowSync` queue, allowing fine-grained control over individual streams.

#### The `updSyncQ` Queue

The `updSyncQ` queue is a dropping queue with capacity 1, used to coordinate window updates and prevent excessive WINDOW_UPDATE frames.

**Key characteristics:**

1. **Dropping Behavior**: As a `Queue.dropping` with capacity 1, it will silently drop offers when full, preventing queue overflow.

2. **Coordination Role**: It's passed to each `Http2Stream` instance during creation:

```scala
// During stream creation
Http2Stream(
  active,
  d,
  header,
  trailing_header,
  inDataQ = dataIn,
  outXFlowSync = xFlowSync,
  transmitWindow,
  updSyncQ,  // Window update coordination queue
  pendingInBytes,
  inboundWindow = localInboundWindowSize,
  contentLenFromHeader,
  trailingHdr
)
```

3. **Synchronization Mechanism**: It helps synchronize window updates across multiple streams, ensuring that updates are processed in an orderly fashion.

4. **Preventing Update Storms**: By dropping excessive update signals, it prevents the system from being overwhelmed with window update operations during high-throughput scenarios.

### 3. Window Update Mechanism

The `windowsUpdate` method is the core of the flow control implementation:

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
      .update(_ + upd) *> Logger[IO].debug(s"Send UPDATE_WINDOW $upd streamId= $streamId")).whenA(send_update)
  } yield (send_update)
```

This method:
1. Updates the bytes received counter
2. Updates the available window
3. Determines if a window update should be sent (when window falls below 30% and received bytes below 70%)
4. Sends a WINDOW_UPDATE frame if needed

### 4. Transmit Window Updates

When a WINDOW_UPDATE frame is received, the `updateWindow` method is called:

```scala
private[this] def updateWindow(streamId: Int, inc: Int): IO[Unit] = {
  // Validate increment is non-zero
  IO.raiseError(...).whenA(inc == 0) >> 
  
  // Handle global (connection-level) window update
  (if (streamId == 0)
    updateAndCheckGlobalTx(streamId, inc) >>
      // Update all stream windows when connection window is updated
      streamTbl.values.toSeq
        .traverse(stream =>
          for {
            _ <- stream.transmitWindow.update(_ + inc)
            rs <- stream.transmitWindow.get
            // Check for overflow
            _ <- IO.raiseError(...).whenA(rs >= Integer.MAX_VALUE)
            // Signal that data can be sent
            _ <- stream.outXFlowSync.offer(true)
          } yield ()
        )
        .void
  // Handle stream-level window update
  else updateWindowStream(streamId, inc))
}
```

This method:
1. Updates the transmit window based on received WINDOW_UPDATE frames
2. Signals via `outXFlowSync` that more data can be sent
3. Handles both connection-level and stream-level window updates

### 5. Stream-Level Window Management

```scala
private[this] def updateWindowStream(streamId: Int, inc: Int) = {
  streamTbl.get(streamId) match {
    case None => Logger[IO].debug(s"Update window, streamId=$streamId invalid or closed already")
    case Some(stream) =>
      for {
        _ <- stream.transmitWindow.update(_ + inc)
        rs <- stream.transmitWindow.get
        // Check for overflow
        _ <- IO.raiseError(...).whenA(rs >= Integer.MAX_VALUE)
        // Signal that data can be sent
        _ <- stream.outXFlowSync.offer(true)
      } yield ()
  }
}
```

This method updates the transmit window for a specific stream and signals that more data can be sent.

## Flow Control Process

### Receiving Data

1. When data is received, `windowsUpdate` is called for both the connection and the stream
2. The inbound window is decremented by the size of the received data
3. If the window falls below a threshold (30%), a WINDOW_UPDATE frame is sent

### Sending Data - The `xFlowSync` Queue in Action

The data transmission process in Quartz-H2 demonstrates how the `xFlowSync` queue implements reactive flow control:

```scala
private def txWindow_Transmit(stream: Http2StreamCommon, bb: ByteBuffer, data_len: Int): IO[Long] = {
  for {
    tx_g <- globalTransmitWindow.get
    tx_l <- stream.transmitWindow.get
    bytesCredit <- IO(Math.min(tx_g, tx_l))

    _ <-
      if (bytesCredit > 0)
        (for {
          rlen <- IO(Math.min(bytesCredit, data_len))
          frames <- IO(splitDataFrames(bb, rlen))
          _ <- sendFrame(frames._1.buffer)
          _ <- globalTransmitWindow.update(_ - rlen)
          _ <- stream.transmitWindow.update(_ - rlen)

          _ <- frames._2 match {
            case Some(f0) => for {
              b <- stream.outXFlowSync.take  // Wait for flow control signal
              _ <-  IO.raiseError(QH2InterruptException()).whenA(b == false)
              _<- txWindow_Transmit(stream, f0.buffer, f0.dataLen)
            } yield()  
            case None => IO.unit
          }

        } yield ())
      else for {
        b <- stream.outXFlowSync.take  // Wait for flow control signal
        _ <-  IO.raiseError(QH2InterruptException()).whenA(b == false)
        _ <- txWindow_Transmit(stream, bb, data_len)
      } yield()  

  } yield (bytesCredit)
}
```

The process works as follows:

1. **Check Available Credit**: The method first checks both the global and stream-specific transmit windows to determine available credit.

2. **If Credit Available**:
   - Send as much data as possible within the available credit
   - Update both global and stream transmit windows
   - If there's remaining data to send, wait on `outXFlowSync.take` for the next opportunity

3. **If No Credit Available**:
   - Wait on `outXFlowSync.take` for a signal that the window has been updated
   - When a signal is received, retry the transmission

4. **Cancellation Handling**:
   - If `false` is received from `outXFlowSync.take`, raise a `QH2InterruptException` to cancel the operation

### Window Update Process

When a WINDOW_UPDATE frame is received, the following process occurs:

```scala
private[this] def updateWindowStream(streamId: Int, inc: Int) = {
  streamTbl.get(streamId) match {
    case None => Logger[IO].debug(s"Update window, streamId=$streamId invalid or closed already")
    case Some(stream) =>
      for {
        _ <- stream.transmitWindow.update(_ + inc)
        rs <- stream.transmitWindow.get
        _ <- IO.raiseError(...).whenA(rs >= Integer.MAX_VALUE)
        _ <- stream.outXFlowSync.offer(true)  // Signal that data can be sent
      } yield ()
  }
}
```

1. **Update Window**: The transmit window is increased by the increment value
2. **Check for Overflow**: Ensure the window doesn't exceed the maximum allowed value
3. **Signal Availability**: Offer `true` to the `outXFlowSync` queue, signaling that data can be sent

## Backpressure Mechanism

### Role of the `xFlowSync` Queue in Backpressure

The flow control system implements backpressure primarily through the `xFlowSync` queue, which creates a reactive feedback loop between receivers and senders:

1. **Window Size Tracking**: Both inbound and outbound windows are tracked using `Ref[IO, Long]` to prevent overwhelming receivers.

2. **Reactive Signaling Loop**:
   - When a sender wants to transmit data, it first checks if sufficient window is available
   - If not enough window is available, the sender calls `outXFlowSync.take` and waits
   - When a receiver processes data, it may send a WINDOW_UPDATE frame
   - When a WINDOW_UPDATE frame is processed, `outXFlowSync.offer(true)` is called
   - This signals waiting senders that they can try again to send data
   - This creates a natural feedback loop that automatically throttles senders based on receiver capacity

3. **Threshold-Based Updates**: Window updates are sent when the available window falls below 30%, preventing frequent small updates.

4. **Cancellation Support**: The `outXFlowSync` queue can also signal cancellation by offering `false`, which causes waiting senders to abort their operations.

### Role of the `updSyncQ` Queue in Preventing Update Storms

The `updSyncQ` queue complements the backpressure system by preventing excessive window updates:

1. **Dropping Queue**: As a `Queue.dropping[IO, Unit](1)`, it has a capacity of 1 and silently drops offers when full.

2. **Coordination**: It helps coordinate window updates across multiple streams, ensuring that the system doesn't get overwhelmed with update operations.

3. **Efficiency**: By dropping excessive update signals during high-throughput scenarios, it prevents the system from wasting resources on redundant window updates.

This combination of reactive signaling through `xFlowSync` and update throttling through `updSyncQ` creates a robust, efficient backpressure system that adapts to varying network conditions and workloads.

## Cancellation Mechanism

The Quartz-H2 implementation includes a sophisticated cancellation mechanism that ensures proper cleanup of resources when streams are terminated or when the connection is shut down.

### Double `offer(false)` Pattern

A key aspect of the cancellation mechanism is the double `offer(false)` pattern used in the client implementation:

```scala
// In Http2ClientConnection.dropStreams()
_ <- streams.traverse(s0 => s0.outXFlowSync.offer(false) *> s0.outXFlowSync.offer(false))
```

This pattern is necessary due to the recursive structure of the `txWindow_Transmit` method, which has two distinct blocking points where it waits on the `outXFlowSync` queue:

```scala
// First blocking point - when processing remaining data after a partial send
b <- stream.outXFlowSync.take  // Line 130
_ <- IO.raiseError(QH2InterruptException()).whenA(b == false)

// Second blocking point - when no credit is available
b <- stream.outXFlowSync.take  // Line 139
_ <- IO.raiseError(QH2InterruptException()).whenA(b == false)
```

### How Cancellation Works

1. **Signal Propagation**: When a stream or connection needs to be cancelled, `false` values are offered to the `outXFlowSync` queue.

2. **Exception Raising**: When a `false` value is received by a waiting transmitter, it raises a `QH2InterruptException`:
   ```scala
   _ <- IO.raiseError(QH2InterruptException()).whenA(b == false)
   ```

3. **Recursive Cancellation**: Since `txWindow_Transmit` can be in a recursive call chain, two `offer(false)` calls are needed to ensure that all potential blocking points are addressed:
   - The first `offer(false)` unblocks transmissions waiting to send remaining data
   - The second `offer(false)` unblocks transmissions waiting for window credit

### Practical Example

Consider this scenario during connection shutdown:

- Stream A is blocked at the first point, waiting to send remaining data
- Stream B is blocked at the second point, waiting for window credit

A single `offer(false)` per stream would only unblock one of these points, potentially leaving the other transmission hanging indefinitely. The double call ensures that both potential blocking points are addressed, guaranteeing that all transmission operations are properly cancelled.

### Benefits of This Approach

1. **Clean Resource Management**: Ensures that no operations are left hanging during shutdown

2. **Prevents Memory Leaks**: By properly terminating all operations, it prevents memory leaks that could occur if operations were left in a blocked state

3. **Graceful Degradation**: Allows the system to gracefully handle connection errors by properly cleaning up all in-flight operations

4. **Functional Composition**: Leverages cats-effect's functional error handling to propagate cancellation signals through the operation chain

This cancellation mechanism is a critical component of the flow control system, ensuring that the system can properly handle exceptional conditions and maintain resource integrity even during unexpected termination scenarios.

## Preventing Flow Control Errors

Quartz-H2 implements several safeguards against flow control errors:

1. **Window Overflow Detection**: Checks prevent the window from exceeding 2^31-1 (Integer.MAX_VALUE)
2. **Zero Increment Prevention**: WINDOW_UPDATE frames with zero increments are rejected
3. **Stream State Validation**: Window updates for closed streams are handled gracefully

## Conclusion

HTTP/2 flow control in Quartz-H2 is implemented using a combination of reference counters for window tracking, queues for synchronization, and functional reactive patterns for backpressure. This implementation ensures efficient data transfer while preventing receivers from being overwhelmed with data.

The use of cats-effect's `IO` monad and fs2's `Queue` provides a robust, composable approach to flow control that aligns with the functional programming paradigm of the Quartz-H2 server.
