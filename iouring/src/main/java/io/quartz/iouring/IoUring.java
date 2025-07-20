package io.quartz.iouring;

import io.quartz.iouring.util.ReferenceCounter;
import io.quartz.iouring.util.NativeLibraryLoader;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Primary interface for creating and working with an {@code io_uring}.
 */
public class IoUring {
	private static final int DEFAULT_MAX_EVENTS = 1024;
	private static final int EVENT_TYPE_ACCEPT = 0;
	private static final int EVENT_TYPE_READ = 1;
	private static final int EVENT_TYPE_WRITE = 2;
	private static final int EVENT_TYPE_CONNECT = 3;
	private static final int EVENT_TYPE_CLOSE = 4;
	private static final int EVENT_TYPE_ERROR = -1;
	private long lastBatchTS;
	private final long ring;
	private final int ringSize;
	private final ConcurrentHashMap<Integer, AbstractIoUringChannel> fdToSocket = new ConcurrentHashMap<>();
	private Consumer<Exception> exceptionHandler;
	private boolean closed = false;
	private final long cqes;
	private final ByteBuffer resultBuffer;

	/**
	 * Instantiates a new {@code IoUring} with {@code DEFAULT_MAX_EVENTS}.
	 */
	public IoUring() {
		this(DEFAULT_MAX_EVENTS);
	}

	/**
	 * Instantiates a new Io uring.
	 *
	 * @param ringSize the max events
	 */
	public IoUring(int ringSize) {
		this.ringSize = ringSize;
		this.ring = IoUring.create(ringSize);
		this.cqes = IoUring.createCqes(ringSize);
		this.resultBuffer = ByteBuffer.allocateDirect(ringSize * 17);
	}

	/**
	 * Closes the io_uring instance and releases all resources.
	 * Throws IllegalStateException if already closed.
	 */
	public void close() {
		if (closed) {
			throw new IllegalStateException("io_uring closed");
		}
		closed = true;
		IoUring.close(ring);
		IoUring.freeCqes(cqes);
	}

	/**
	 * Submits all queued I/O operations to the kernel without waiting for completion.
	 */
	public void submit() {
		IoUring.submit(ring);
	}

	/**
	 * Takes over the current thread with a loop calling {@code execute()}, until
	 * closed.
	 */
	public void loop() {
		while (!closed) {
			execute();
		}
	}

	/**
	 * Submits all queued I/O operations to the kernel and waits an unlimited amount
	 * of time for any to complete.
	 */
	public int execute() {
		return doExecute(true);
	}

	/**
	 * Submits all queued I/O operations to the kernel and handles any pending
	 * completion events, returning immediately if none are present.
	 */
	public int executeNow() {
		return doExecute(false);
	}

	/**
	 * Retrieves completion queue events with the specified timeout.
	 * @param timeoutMs Timeout in milliseconds to wait for events
	 * @return Status code: 1 if read event occurred, -1 if timeout, 0 otherwise
	 */
	public int getCqes(long timeoutMs) {
		return doGetCqes(true, timeoutMs);
	}

	private int doGetCqes(boolean shouldWait, long timeoutMs) {
		// not used, but can be usefull if you track reads and writes in different
		// places
		int didReadEverHappen = 0;

		if (lastBatchTS == 0)
			lastBatchTS = System.nanoTime();

		if (closed) {
			throw new IllegalStateException("io_uring closed");
		}
		try {
			int count = IoUring.getCqes(ring, resultBuffer, cqes, ringSize, shouldWait, timeoutMs);
			if (count == 0) {
				didReadEverHappen = -1;
				handleReadTimeouts(resultBuffer, timeoutMs);
			}
			for (int i = 0; i < count && i < ringSize; i++) {
				try {
					int eventType = handleEventCompletion(cqes, resultBuffer, i);
					// use the opportunity to close expired connection sitting on the same ring.
					if (eventType == EVENT_TYPE_READ)
						didReadEverHappen = 1;

					if (System.nanoTime() - lastBatchTS > (timeoutMs * 1000000)) {
						handleReadTimeouts(resultBuffer, timeoutMs);
						lastBatchTS = System.nanoTime();
					}
				} finally {
					IoUring.markCqeSeen(ring, cqes, i);
				}
			}
		} catch (Exception ex) {
			if (exceptionHandler != null) {
				exceptionHandler.accept(ex);
			}
		} finally {
			resultBuffer.clear();
		}
		return didReadEverHappen;
	}

	/**
	 * Submits queued operations and processes completion events.
	 * @param shouldWait Whether to wait for events if none are immediately available
	 * @return Number of processed events or -1 on error
	 */
	private int doExecute(boolean shouldWait) {
		if (closed) {
			throw new IllegalStateException("io_uring closed");
		}
		try {
			int count = IoUring.submitAndGetCqes(ring, resultBuffer, cqes, ringSize, shouldWait);
			for (int i = 0; i < count && i < ringSize; i++) {
				try {
					handleEventCompletion(cqes, resultBuffer, i);
				} finally {
					IoUring.markCqeSeen(ring, cqes, i);
				}
			}
			return count;
		} catch (Exception ex) {
			if (exceptionHandler != null) {
				exceptionHandler.accept(ex);
			}
		} finally {
			resultBuffer.clear();
		}
		return -1;
	}

	/**
	 * Checks all registered channels for read timeouts and closes inactive ones.
	 * Also cleans up closed channels from the registry.
	 * @param results Buffer containing event results (unused)
	 * @param timeoutMs Timeout threshold in milliseconds
	 */
	private void handleReadTimeouts(ByteBuffer results, long timeoutMs) {
		for (AbstractIoUringChannel channel : fdToSocket.values()) {
			if (channel == null)
				continue;
			if (channel.isClosed()) {
				deregister(channel);
				continue;
			}
			long time = System.nanoTime();

			if (time - channel.ts > timeoutMs * 1000000L) {
				channel.readHandler().accept(null);
				channel.close();
			} else {
				// channel is still active
			}
		}
	}

	/**
	 * Processes a single completion queue event based on its type.
	 * Handles accept, connect, read, write, and close events.
	 * @param cqes Native completion queue event set pointer
	 * @param results Buffer containing event data
	 * @param i Index of the event to process
	 * @return The type of event that was processed
	 */
	private int handleEventCompletion(long cqes, ByteBuffer results, int i) {
		int result = results.getInt();
		int fd = results.getInt();
		int eventType = results.get();

		if (eventType == EVENT_TYPE_ACCEPT) {
			IoUringServerSocket serverSocket = (IoUringServerSocket) fdToSocket.get(fd);
			String ipAddress = IoUring.getCqeIpAddress(cqes, i);
			IoUringSocket socket = serverSocket.handleAcceptCompletion(this, serverSocket, result, ipAddress);
			if (socket != null) {
				socket.ts = System.nanoTime();
				fdToSocket.put(socket.fd(), socket);
			}
		} else {
			AbstractIoUringChannel channel = fdToSocket.get(fd);
			if (channel == null || channel.isClosed()) {
				return EVENT_TYPE_ERROR;
			}
			try {
				if (eventType == EVENT_TYPE_CONNECT) {
					((IoUringSocket) channel).handleConnectCompletion(this, result);
				} else if (eventType == EVENT_TYPE_READ) {
					long bufferAddress = results.getLong();
					ReferenceCounter<ByteBuffer> refCounter = channel.readBufferMap().get(bufferAddress);
					ByteBuffer buffer = refCounter.ref();
					if (buffer == null) {
						throw new IllegalStateException("Buffer already removed");
					}
					if (refCounter.deincrementReferenceCount() == 0) {
						channel.readBufferMap().remove(bufferAddress);
					}
					channel.handleReadCompletion(buffer, result);
				} else if (eventType == EVENT_TYPE_WRITE) {
					long bufferAddress = results.getLong();
					ReferenceCounter<ByteBuffer> refCounter = channel.writeBufferMap().get(bufferAddress);
					ByteBuffer buffer = refCounter.ref();
					if (buffer == null) {
						throw new IllegalStateException("Buffer already removed");
					}
					if (refCounter.deincrementReferenceCount() == 0) {
						channel.writeBufferMap().remove(bufferAddress);
					}
					channel.handleWriteCompletion(buffer, result);
				} else if (eventType == EVENT_TYPE_CLOSE) {
					// System.out.println( "EVENT_TYPE_CLOSE");
					channel.close();
					// channel.closeHandler().run();
					// channel.setClosed(true);
				}
			} catch (Exception ex) {
				if (channel.exceptionHandler() != null) {
					channel.exceptionHandler().accept(ex);
				}
			} finally {
				if (channel.isClosed() && channel.equals(fdToSocket.get(fd))) {
					deregister(channel);
				}
			}
		}
		return eventType;
	}

	/**
	 * Adds a server socket to the io_uring for accepting new connections.
	 * Registers the server socket in the channel registry.
	 * @param serverSocket The server socket to queue for accept operations
	 * @return This IoUring instance for method chaining
	 */
	public IoUring queueAccept(IoUringServerSocket serverSocket) {
		fdToSocket.put(serverSocket.fd(), serverSocket);
		IoUring.queueAccept(ring, serverSocket.fd());
		return this;
	}

	/**
	 * Queues a socket for connection to a remote endpoint.
	 * Registers the socket in the channel registry.
	 * @param socket The socket to connect
	 * @return This IoUring instance for method chaining
	 */
	public IoUring queueConnect(IoUringSocket socket) {
		fdToSocket.put(socket.fd(), socket);
		IoUring.queueConnect(ring, socket.fd(), socket.ipAddress(), socket.port());
		return this;
	}

	/**
	 * Queues a read operation on a channel with no offset.
	 * @param channel The channel to read from
	 * @param buffer The buffer to read data into
	 * @return This IoUring instance for method chaining
	 */
	public IoUring queueRead(AbstractIoUringChannel channel, ByteBuffer buffer) {
		return queueRead(channel, buffer, 0L);
	}

	/**
	 * Queues a read operation on a channel with specified offset.
	 * Registers the channel in the registry and tracks the buffer reference.
	 * @param channel The channel to read from
	 * @param buffer The direct ByteBuffer to read data into
	 * @param offset The offset in the file/source to read from
	 * @return This IoUring instance for method chaining
	 * @throws IllegalArgumentException if buffer is not direct
	 */
	public IoUring queueRead(AbstractIoUringChannel channel, ByteBuffer buffer, long offset) {
		if (!buffer.isDirect()) {
			throw new IllegalArgumentException("Buffer must be direct");
		}
		fdToSocket.put(channel.fd(), channel);
		long bufferAddress = IoUring.queueRead(ring, channel.fd(), buffer, buffer.position(),
				buffer.limit() - buffer.position(), offset);
		ReferenceCounter<ByteBuffer> refCounter = channel.readBufferMap().get(bufferAddress);
		if (refCounter == null) {
			refCounter = new ReferenceCounter<>(buffer);
			channel.readBufferMap().put(bufferAddress, refCounter);
		}
		refCounter.incrementReferenceCount();
		return this;
	}

	/**
	 * Queues a write operation on a channel with no offset.
	 * @param channel The channel to write to
	 * @param buffer The buffer containing data to write
	 * @return This IoUring instance for method chaining
	 */
	public IoUring queueWrite(AbstractIoUringChannel channel, ByteBuffer buffer) {
		return queueWrite(channel, buffer, 0L);
	}

	/**
	 * Queues a write operation on a channel with specified offset.
	 * Registers the channel in the registry and tracks the buffer reference.
	 * @param channel The channel to write to
	 * @param buffer The direct ByteBuffer containing data to write
	 * @param offset The offset in the file/target to write to
	 * @return This IoUring instance for method chaining
	 * @throws IllegalArgumentException if buffer is not direct
	 */
	public IoUring queueWrite(AbstractIoUringChannel channel, ByteBuffer buffer, long offset) {
		if (!buffer.isDirect()) {
			throw new IllegalArgumentException("Buffer must be direct");
		}
		fdToSocket.put(channel.fd(), channel);
		long bufferAddress = IoUring.queueWrite(ring, channel.fd(), buffer, buffer.position(),
				buffer.limit() - buffer.position(), offset);
		ReferenceCounter<ByteBuffer> refCounter = channel.writeBufferMap().get(bufferAddress);
		if (refCounter == null) {
			refCounter = new ReferenceCounter<>(buffer);
			channel.writeBufferMap().put(bufferAddress, refCounter);
		}
		refCounter.incrementReferenceCount();
		return this;
	}

	/**
	 * Queues a close operation for a channel.
	 * @param channel The channel to close
	 * @return This IoUring instance for method chaining
	 */
	public IoUring queueClose(AbstractIoUringChannel channel) {
		// System.out.println( "public IoUring queueClose(AbstractIoUringChannel
		// channel)");
		IoUring.queueClose(ring, channel.fd());
		return this;
	}

	/**
	 * Gets the current exception handler.
	 * @return The exception handler function
	 */
	Consumer<Exception> exceptionHandler() {
		return exceptionHandler;
	}

	/**
	 * Sets a handler for exceptions that occur during io_uring operations.
	 * @param exceptionHandler The exception handler function
	 * @return This IoUring instance for method chaining
	 */
	public IoUring onException(Consumer<Exception> exceptionHandler) {
		this.exceptionHandler = exceptionHandler;
		return this;
	}

	/**
	 * Removes a channel from the registry when it's no longer needed.
	 * @param channel The channel to deregister
	 */
	void deregister(AbstractIoUringChannel channel) {
		fdToSocket.remove(channel.fd());
	}

	private static native long create(int maxEvents);

	private static native void close(long ring);

	private static native void submit(long ring);

	private static native long createCqes(int count);

	private static native void freeCqes(long cqes);

	private static native int submitAndGetCqes(long ring, ByteBuffer buffer, long cqes, int cqesSize,
			boolean shouldWait);

	private static native int getCqes(long ring, ByteBuffer buffer, long cqes, int cqesSize, boolean shouldWait,
			long timeoutMs);

	private static native int getCqesBlocking(long ring, ByteBuffer buffer, long cqes, int cqesSize);

	private static native String getCqeIpAddress(long cqes, int cqeIndex);

	private static native void markCqeSeen(long ring, long cqes, int cqeIndex);

	private static native void queueAccept(long ring, int serverSocketFd);

	private static native void queueConnect(long ring, int socketFd, String ipAddress, int port);

	private static native long queueRead(long ring, int channelFd, ByteBuffer buffer, int bufferPos, int bufferLen,
			long offset);

	private static native long queueWrite(long ring, int channelFd, ByteBuffer buffer, int bufferPos, int bufferLen,
			long offset);

	private static native void queueClose(long ring, int channelFd);

	static {
		NativeLibraryLoader.load();
	}
}
