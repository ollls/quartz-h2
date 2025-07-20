#include "liburing_socket_provider.h"
#include "liburing_provider.h"

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <liburing.h>
#include <unistd.h>
#include <string.h>
#include <stdint.h>

JNIEXPORT jint JNICALL
Java_io_quartz_iouring_AbstractIoUringSocket_create(JNIEnv *env, jclass cls) {
    int32_t val = 1;

    int32_t fd = socket(PF_INET, SOCK_STREAM, 0);
    if (fd == -1) {
        throw_exception(env, "socket", fd);
        return -1;
    }

    int32_t ret = setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &val, sizeof(int));
    if (ret < 0) {
        throw_exception(env, "setsockopt", ret);
        return -1;
    }

    ret = setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, &val, sizeof(val));
    if (ret == -1) {
        throw_exception(env, "setsockopt", ret);
        return -1;
    }

    int flag = 1; // Set to 1 to enable TCP_NODELAY (disable Nagle's algorithm)
    ret = setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &flag, sizeof(int));
    if (ret == -1) {
        throw_exception(env, "setsockopt", ret);
        return -1;
    }

    return (uint32_t) fd;
}

JNIEXPORT void JNICALL
Java_io_quartz_iouring_IoUringServerSocket_bind(JNIEnv *env, jclass cls, jlong server_socket_fd, jstring ip_address, jint port, jint backlog) {
    const char *ip = (*env)->GetStringUTFChars(env, ip_address, NULL);

    struct sockaddr_in srv_addr;
    memset(&srv_addr, 0, sizeof(srv_addr));
    srv_addr.sin_family = AF_INET;
    srv_addr.sin_port = htons(port);
    srv_addr.sin_addr.s_addr = inet_addr(ip);

    (*env)->ReleaseStringUTFChars(env, ip_address, ip);

    int32_t ret = bind(server_socket_fd, (const struct sockaddr *) &srv_addr, sizeof(srv_addr));
    if (ret < 0) {
        throw_exception(env, "bind", ret);
        return;
    }

    ret = listen(server_socket_fd, backlog);
    if (ret < 0) {
        throw_exception(env, "io_uring_get_sqe", -16);
        return;
    }
} 


JNIEXPORT jint JNICALL
Java_io_quartz_iouring_AbstractIoUringSocket_setSocketBuffers(JNIEnv *env, jclass cls, jint fd, jint rcv_buf_size, jint snd_buf_size) {
    // Set receive buffer size (SO_RCVBUF)
    int ret = setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &rcv_buf_size, sizeof(rcv_buf_size));
    if (ret < 0) {
        throw_exception(env, "setsockopt SO_RCVBUF failed", ret);
        return -1;
    }
    
    // Set send buffer size (SO_SNDBUF)
    ret = setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &snd_buf_size, sizeof(snd_buf_size));
    if (ret < 0) {
        throw_exception(env, "setsockopt SO_SNDBUF failed", ret);
        return -1;
    }
    return 0;
}


/*
 * Method:    setSocketTimeout
 */
JNIEXPORT jint JNICALL
Java_io_quartz_iouring_AbstractIoUringSocket_setSocketTimeout(JNIEnv *env, jclass cls, jint fd, jint timeoutMilliseconds) {
    struct timeval tv;

    tv.tv_sec = timeoutMilliseconds / 1000;  
    tv.tv_usec = (timeoutMilliseconds % 1000) * 1000;
 
    // Set receive timeout (SO_RCVTIMEO)
    int ret = setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    if (ret < 0) {
        throw_exception(env, "setsockopt timeout", ret);
        return -1;
    }
    
    // Set send timeout (SO_SNDTIMEO)
    ret = setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    if (ret < 0) {
        throw_exception(env, "setsockopt timeout", ret);
        return -1;
    }
    
    return 0;
}
