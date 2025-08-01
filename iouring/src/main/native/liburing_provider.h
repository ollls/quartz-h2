#ifndef _LIBURING_PROVIDER_DEFINED
#define _LIBURING_PROVIDER_DEFINED

#include <jni.h>
#include <stdint.h>

JNIEXPORT jlong JNICALL
Java_io_quartz_iouring_IoUring_createCqes(JNIEnv *env, jclass cls, jint count);

JNIEXPORT void JNICALL
Java_io_quartz_iouring_IoUring_freeCqes(JNIEnv *env, jclass cls, jlong cqes_address);

JNIEXPORT jlong JNICALL
Java_io_quartz_iouring_IoUring_create(JNIEnv *env, jclass cls, jint maxEvents);

JNIEXPORT void JNICALL
Java_io_quartz_iouring_IoUring_close(JNIEnv *env, jclass cls, jlong ring_address);

JNIEXPORT jint JNICALL
Java_io_quartz_iouring_IoUring_submitAndGetCqes(JNIEnv *env, jclass cls, jlong ring_address, jobject byte_buffer, jlong cqes_address, jint cqes_size, jboolean should_wait);

JNIEXPORT jstring JNICALL
Java_io_quartz_iouring_IoUring_getCqeIpAddress(JNIEnv *env, jclass cls, jlong cqes_address, jint cqe_index);

JNIEXPORT void JNICALL
Java_io_quartz_iouring_IoUring_markCqeSeen(JNIEnv *env, jclass cls, jlong ring_address, jlong cqes_address, jint cqe_index);

JNIEXPORT void JNICALL
Java_io_quartz_iouring_IoUring_queueAccept(JNIEnv *env, jclass cls, jlong ring_address, jint server_socket_fd);

JNIEXPORT void JNICALL
Java_io_quartz_iouring_IoUring_queueConnect(JNIEnv *env, jclass cls, jlong ring_address, jint socket_fd, jstring ip_address, jint port);

JNIEXPORT jlong JNICALL
Java_io_quartz_iouring_IoUring_queueRead(JNIEnv *env, jclass cls, jlong ring_address, jint fd, jobject byte_buffer, jint buffer_pos, jint buffer_len, jlong io_offset);

JNIEXPORT jlong JNICALL
Java_io_quartz_iouring_IoUring_queueWrite(JNIEnv *env, jclass cls, jlong ring_address, jint fd, jobject byte_buffer, jint buffer_pos, jint buffer_len, jlong io_offset);

JNIEXPORT void JNICALL
Java_io_quartz_iouring_IoUring_queueClose(JNIEnv *env, jclass cls, jlong ring_address, jint fd);

JNIEXPORT void JNICALL
Java_io_quartz_iouring_AbstractIoUringChannel_close(JNIEnv *env, jclass cls, jint fd);

int32_t throw_exception(JNIEnv *env, char *cause, int32_t ret);

int32_t throw_out_of_memory_error(JNIEnv *env);

#endif
