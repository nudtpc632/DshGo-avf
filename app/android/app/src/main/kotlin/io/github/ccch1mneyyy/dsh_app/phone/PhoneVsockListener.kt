package io.github.ccch1mneyyy.dsh_app.phone

import io.github.ccch1mneyyy.dsh_app.runtime.avf.Vsock
import io.github.ccch1mneyyy.dsh_app.runtime.avf.VsockConnection
import io.github.ccch1mneyyy.dsh_app.runtime.avf.VsockListener
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * AVF transport for the phone bridge: the guest relay cannot reach the App's
 * abstract unix socket across the VM boundary, and Node in the guest has no
 * AF_VSOCK, so the guest C bridge (vsock-tcp-bridge -c 13083 3083) listens on
 * guest loopback 13083 and dials vsock host:[PORT]; this listener accepts and
 * pumps each connection into the bridge's loopback TCP listener. The
 * hello-token and admission semantics stay in [PhoneBridge]; the extra hop is
 * same-App code, like the PRoot rootfs.
 *
 * Transport contract (vm-manifest.json vsock.transportContract): the vsock side
 * is never half-closed; pumps run until either side fully closes.
 */
class PhoneVsockListener(private val log: (String) -> Unit) {
    @Volatile private var listener: VsockListener? = null
    @Volatile private var running = false

    /** Accepts guest vsock:[PORT] connections and forwards them to the bridge's loopback TCP [tcpPort]. */
    @Synchronized fun start(tcpPort: Int): Boolean {
        check(!running) { "already started" }
        if (!Vsock.available()) return false
        val l = try {
            Vsock.listen(PORT)
        } catch (e: Exception) {
            log("[dsh-app] phone vsock listener: bind failed (${e.message})")
            return false
        }
        listener = l
        running = true
        thread(name = "dsh-phone-vsock-accept", isDaemon = true) {
            while (running) {
                val guest = try { l.accept() } catch (_: Exception) { break }
                thread(name = "dsh-phone-vsock", isDaemon = true) { serve(guest, tcpPort) }
            }
        }
        log("[dsh-app] phone vsock listener on vsock:$PORT -> 127.0.0.1:$tcpPort")
        return true
    }

    @Synchronized fun stop() {
        running = false
        try { listener?.close() } catch (_: Exception) {}
        listener = null
    }

    private fun serve(guest: VsockConnection, tcpPort: Int) {
        val bridge = Socket()
        try {
            bridge.connect(InetSocketAddress("127.0.0.1", tcpPort), 5_000)
        } catch (_: Exception) {
            close(guest)
            return
        }
        bridge.tcpNoDelay = true
        pump(guest, guest.input, bridge, bridge.getOutputStream())
        pump(bridge, bridge.getInputStream(), guest, guest.output)
    }

    private fun pump(from: Closeable, input: InputStream, to: Closeable, output: OutputStream) {
        thread(name = "dsh-phone-vsock-pump", isDaemon = true) {
            try {
                val buf = ByteArray(16384)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    output.flush()
                }
            } catch (_: Exception) {
            } finally {
                close(from)
                close(to)
            }
        }
    }

    private fun close(c: Closeable) {
        try { c.close() } catch (_: Exception) {}
    }

    companion object {
        const val PORT = 3083
    }
}