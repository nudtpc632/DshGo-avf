// Host-side AF_VSOCK client for the AVF backend (JNI, libvsock_client.so).
// Java has no AF_VSOCK socket; this exposes connect/read/write/close on a
// raw vsock fd so Kotlin can pump HTTP and the vm-ctl channel over it.
//
// Transport contract (docs/design/avf-backend.md §6, vm-manifest.json
// vsock.transportContract): after writing the request the caller MUST NOT
// shutdown(SHUT_WR). The guest kernel drops in-flight data after a
// write-half-close (large responses almost always lost); both guest servers
// close their side after responding, so read until EOF instead.
#include <jni.h>
#include <errno.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <linux/vm_sockets.h>

// Returns the connected fd (>= 0), or -errno on failure.
JNIEXPORT jint JNICALL
Java_io_github_ccch1mneyyy_dsh_1app_runtime_avf_Vsock_connectNative(JNIEnv *env, jclass cls, jint cid, jint port) {
    (void)env; (void)cls;
    int fd = socket(AF_VSOCK, SOCK_STREAM, 0);
    if (fd < 0) return -errno;
    struct sockaddr_vm sa;
    memset(&sa, 0, sizeof sa);
    sa.svm_family = AF_VSOCK;
    sa.svm_cid = (unsigned)cid;
    sa.svm_port = (unsigned)port;
    if (connect(fd, (struct sockaddr *)&sa, sizeof sa) < 0) {
        int e = errno;
        close(fd);
        return -e;
    }
    return fd;
}

// Returns bytes read, 0 on EOF, -errno on error.
JNIEXPORT jint JNICALL
Java_io_github_ccch1mneyyy_dsh_1app_runtime_avf_Vsock_read(JNIEnv *env, jclass cls, jint fd, jbyteArray buf, jint len) {
    (void)cls;
    if (len <= 0 || len > (*env)->GetArrayLength(env, buf)) return -EINVAL;
    jbyte *p = (*env)->GetByteArrayElements(env, buf, NULL);
    if (!p) return -ENOMEM;
    ssize_t n = read(fd, p, (size_t)len);
    int e = errno;
    (*env)->ReleaseByteArrayElements(env, buf, p, n > 0 ? 0 : JNI_ABORT);
    return n < 0 ? -e : (jint)n;
}

// Writes the whole slice or fails; returns bytes written or -errno.
JNIEXPORT jint JNICALL
Java_io_github_ccch1mneyyy_dsh_1app_runtime_avf_Vsock_write(JNIEnv *env, jclass cls, jint fd, jbyteArray buf, jint off, jint len) {
    (void)cls;
    if (off < 0 || len < 0 || off + len > (*env)->GetArrayLength(env, buf)) return -EINVAL;
    jbyte *p = (*env)->GetByteArrayElements(env, buf, NULL);
    if (!p) return -ENOMEM;
    ssize_t done = 0;
    int err = 0;
    while (done < len) {
        ssize_t w = write(fd, p + off + done, (size_t)(len - done));
        if (w < 0) {
            if (errno == EINTR) continue;
            err = errno;
            break;
        }
        done += w;
    }
    (*env)->ReleaseByteArrayElements(env, buf, p, JNI_ABORT);
    return err ? -err : (jint)done;
}

JNIEXPORT void JNICALL
Java_io_github_ccch1mneyyy_dsh_1app_runtime_avf_Vsock_close(JNIEnv *env, jclass cls, jint fd) {
    (void)env; (void)cls;
    close(fd);
}

// Binds a vsock listener on VMADDR_CID_ANY:[port] (the guest dials the host as
// CID 2). Returns the listening fd (>= 0), or -errno on failure.
JNIEXPORT jint JNICALL
Java_io_github_ccch1mneyyy_dsh_1app_runtime_avf_Vsock_listenNative(JNIEnv *env, jclass cls, jint port) {
    (void)env; (void)cls;
    int fd = socket(AF_VSOCK, SOCK_STREAM, 0);
    if (fd < 0) return -errno;
    struct sockaddr_vm sa;
    memset(&sa, 0, sizeof sa);
    sa.svm_family = AF_VSOCK;
    sa.svm_cid = VMADDR_CID_ANY;
    sa.svm_port = (unsigned)port;
    if (bind(fd, (struct sockaddr *)&sa, sizeof sa) < 0 || listen(fd, 16) < 0) {
        int e = errno;
        close(fd);
        return -e;
    }
    return fd;
}

// Accepts one guest connection on a listening fd; returns the connected fd
// (>= 0), or -errno on failure. Blocking; the caller runs it on its own thread.
JNIEXPORT jint JNICALL
Java_io_github_ccch1mneyyy_dsh_1app_runtime_avf_Vsock_acceptNative(JNIEnv *env, jclass cls, jint fd) {
    (void)env; (void)cls;
    int c = accept(fd, NULL, NULL);
    return c < 0 ? -errno : c;
}