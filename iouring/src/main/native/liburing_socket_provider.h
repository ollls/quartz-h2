#ifndef _LIBURING_SOCKET_PROVIDER_DEFINED
#define _LIBURING_SOCKET_PROVIDER_DEFINED

#include <jni.h>
#include <stdint.h>

JNIEXPORT jint JNICALL
Java_io_quartz_iouring_AbstractIoUringSocket_create(JNIEnv *env, jclass cls);

JNIEXPORT jint JNICALL 
Java_io_quartz_iouring_AbstractIoUringSocket_setSocketTimeout(JNIEnv *env, jclass cls, jint fd, jint timeoutMilliseconds);

JNIEXPORT void JNICALL
Java_io_quartz_iouring_IoUringServerSocket_bind(JNIEnv *env, jclass cls, jlong server_socket_fd, jstring host, jint port, jint backlog);

int32_t throw_buffer_overflow_exception(JNIEnv *env);

#endif
