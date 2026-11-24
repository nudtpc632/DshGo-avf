package io.github.ccch1mneyyy.dsh_app.runtime.avf

import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Raw AF_VSOCK client (JNI, libvsock_client.so built by runtime/vm/build-vsock-jni.sh).
 * Java has no vsock socket family; this wraps the native fd in streams so HTTP and
 * the vm-ctl channel can be pumped like any TCP connection.
 *
 * Transport contract (vm-manifest.json vsock.transportContract): never half-close
 * the write side after sending a request — the guest kernel drops in-flight data
 * after SHUT_WR (large responses almost always lost). Both guest servers close
 * their side after responding; read to EOF instead. [VsockConnection.close] is a
 * full close, which is always safe.
 */
object Vsock {
    @Volatile private var loaded = false

    fun available(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("vsock_client")
            loaded = true
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /** Connects to guest [cid]:[port]; throws [IOException] when the guest is not there. */
    fun connect(cid: Int, port: Int): VsockConnection {
        check(available()) { "libvsock_client.so not bundled" }
        val fd = connectNative(cid, port)
        if (fd < 0) throw IOException("vsock connect cid=$cid port=$port: ${Os.strerror(-fd)}")
        return VsockConnection(fd)
    }

    /** Binds a host-side vsock listener on [port] (the guest dials CID 2). */
    fun listen(port: Int): VsockListener {
        check(available()) { "libvsock_client.so not bundled" }
        val fd = listenNative(port)
        if (fd < 0) throw IOException("vsock listen port=$port: ${Os.strerror(-fd)}")
        return VsockListener(fd)
    }

    @JvmStatic private external fun connectNative(cid: Int, port: Int): Int
    @JvmStatic @JvmName("listenNative") private external fun listenNative(port: Int): Int
    @JvmStatic @JvmName("acceptNative") internal external fun acceptNative(fd: Int): Int
    // internal: VsockConnection (same module) pumps the fd through these.
    // @JvmName pins the JVM name: Kotlin would otherwise mangle internal members
    // with a module suffix ($android_app_arm64Debug) that the JNI symbols lack.
    @JvmStatic @JvmName("read") internal external fun read(fd: Int, buf: ByteArray, len: Int): Int
    @JvmStatic @JvmName("write") internal external fun write(fd: Int, buf: ByteArray, off: Int, len: Int): Int
    @JvmStatic @JvmName("close") internal external fun close(fd: Int)
}

/** Host-side vsock listener: the guest dials host CID 2:[port]. */
class VsockListener internal constructor(private val fd: Int) : Closeable {
    @Volatile private var closed = false

    /** Blocking accept; throws [IOException] when the listener is closed. */
    fun accept(): VsockConnection {
        val c = Vsock.acceptNative(fd)
        if (c < 0) throw IOException("vsock accept: ${Os.strerror(-c)}")
        return VsockConnection(c)
    }

    override fun close() {
        if (closed) return
        closed = true
        Vsock.close(fd)
    }
}

/** One vsock connection. Streams share the fd; closing either closes the connection. */
class VsockConnection internal constructor(private val fd: Int) : Closeable {
    @Volatile private var closed = false

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xff
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) return -1
            if (len == 0) return 0
            val buf = if (off == 0 && len == b.size) b else ByteArray(len)
            val n = Vsock.read(fd, buf, len)
            if (n < 0) {
                if (-n == OsConstants.EINTR) return read(b, off, len)
                throw IOException("vsock read: ${Os.strerror(-n)}")
            }
            if (n == 0) return -1
            if (buf !== b) System.arraycopy(buf, 0, b, off, n)
            return n
        }
        override fun close() = this@VsockConnection.close()
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(v: Int) = write(byteArrayOf(v.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("vsock closed")
            val n = Vsock.write(fd, b, off, len)
            if (n < 0) throw IOException("vsock write: ${Os.strerror(-n)}")
        }
        override fun close() = this@VsockConnection.close()
    }

    override fun close() {
        if (closed) return
        closed = true
        Vsock.close(fd)
    }
}