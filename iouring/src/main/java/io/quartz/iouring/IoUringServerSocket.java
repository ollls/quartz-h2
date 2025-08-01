package io.quartz.iouring;

import io.quartz.iouring.util.NativeLibraryLoader;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.net.UnknownHostException;

/**
 * A {@code ServerSocket} analog for working with an {@code io_uring}.
 */
public class IoUringServerSocket extends AbstractIoUringSocket {
    private static final int DEFAULT_BACKLOG = 65535;
    private String resolvedIPAddress;

    private BiConsumer<IoUring, IoUringSocket> acceptHandler;

    // Method to resolve hostname to IP address
    private String resolveHostname(String hostname) throws UnknownHostException {
        InetAddress address = InetAddress.getByName(hostname);
        return address.getHostAddress(); // Returns the IP address string
    }

    /**
     * Instantiates a new {@code IoUringServerSocket}.
     *
     * @param address The address to bind to
     * @param port    The port to bind to
     * @param backlog The backlog size
     */
    public IoUringServerSocket(String address, int port, int backlog) throws UnknownHostException {
        super(AbstractIoUringSocket.create(), address, port);
        if (!address.matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) {
            resolvedIPAddress = resolveHostname(address);
            if (resolvedIPAddress == null) {
                throw new RuntimeException("Failed to resolve hostname: " + address);
            }
        } else
            resolvedIPAddress = address;
        IoUringServerSocket.bind(fd(), resolvedIPAddress, port, backlog);
    }


    public String ipAddress() {
        return resolvedIPAddress;
    }

    /**
     * Instantiates a new {@code IoUringServerSocket} with a default backlog size of
     * {@code DEFAULT_BACKLOG}.
     *
     * @param address The address to bind to
     * @param port    The port to bind to
     */
    public IoUringServerSocket(String address, int port) throws UnknownHostException {
        this(address, port, DEFAULT_BACKLOG);
    }

    /**
     * Instantiates a new {@code IoUringServerSocket} bound to "127.0.0.1" on the
     * specified port with the default
     * backlog size of {@code DEFAULT_BACKLOG}.
     *
     * @param port The port to bind to
     */
    public IoUringServerSocket(int port) throws UnknownHostException {
        this("127.0.0.1", port, DEFAULT_BACKLOG);
    }

    IoUringSocket handleAcceptCompletion(IoUring ioUring, IoUringServerSocket serverSocket, int channelFd,
            String ipAddress) {
        this.ts = System.nanoTime();
        if (channelFd < 0) {
            return null;
        }
        IoUringSocket channel = new IoUringSocket(channelFd, ipAddress, serverSocket.port());
        if (serverSocket.acceptHandler() != null) {
            serverSocket.acceptHandler().accept(ioUring, channel);
        }
        return channel;
    }

    @Override
    public IoUringServerSocket onRead(Consumer<ByteBuffer> buffer) {
        throw new UnsupportedOperationException("Server socket cannot read");
    }

    @Override
    public IoUringServerSocket onWrite(Consumer<ByteBuffer> buffer) {
        throw new UnsupportedOperationException("Server socket cannot write");
    }

    /**
     * Gets the accept handler.
     *
     * @return the accept handler
     */
    BiConsumer<IoUring, IoUringSocket> acceptHandler() {
        return acceptHandler;
    }

    /**
     * Sets the accept handler.
     *
     * @param acceptHandler the accept handler
     * @return this instance
     */
    public IoUringServerSocket onAccept(BiConsumer<IoUring, IoUringSocket> acceptHandler) {
        this.acceptHandler = acceptHandler;
        return this;
    }

    private static native void bind(long fd, String host, int port, int backlog);

    static {
        NativeLibraryLoader.load();
    }
}
