package io.quartz.iouring;

import io.quartz.iouring.util.NativeLibraryLoader;

/**
 * An {@link AbstractIoUringChannel} representing a network socket.
 */
public class AbstractIoUringSocket extends AbstractIoUringChannel {
    private final String ipAddress;
    private final int port;

    /**
     * Creates a new {@code AbstractIoUringSocket} instance.
     * 
     * @param fd        The file descriptor
     * @param ipAddress The IP address
     * @param port      The port
     */
    AbstractIoUringSocket(int fd, String ipAddress, int port) {
        super(fd);
        this.ipAddress = ipAddress;
        this.port = port;
        this.ts = System.nanoTime();
    }

    public String ipAddress() {
        return ipAddress;
    }

    public int port() {
        return port;
    }

    public void setTimeout(int timeoutMilliseconds) {
        setSocketTimeout(fd(), timeoutMilliseconds);
    }

    public void setBuffers(int rcvBufSize, int sndBufSize) {
        setSocketBuffers(fd(), rcvBufSize, sndBufSize);
    }

    static native int create();

    static native int setSocketTimeout(int fd, int timeoutMilliseconds);

    static native int setSocketBuffers(int fd, int rcv_buf_size, int snd_buf_size);

    static {
        NativeLibraryLoader.load();
    }
}
