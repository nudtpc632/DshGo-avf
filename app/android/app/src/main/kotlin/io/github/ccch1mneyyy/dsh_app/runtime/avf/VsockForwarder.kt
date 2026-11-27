package io.github.ccch1mneyyy.dsh_app.runtime.avf

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Host-side loopback forwarder for the AVF backend: listens on 127.0.0.1:<port>
 * and pumps each connection over AF_VSOCK to the guest (dsh web on 3080, vm-ctl
 * on 3081). Dart's DshClient keeps talking to loopback, unchanged.
 *
 * Transport contract (vm-manifest.json vsock.transportContract): the vsock side
 * is never half-closed. When the local client finishes writing (EOF), the pump
 * keeps reading the response until the guest closes; only a full close ends the
 * connection. The guest servers close after responding, so no SHUT_WR is needed.
 */
class VsockForwarder(private val cid: Int, private val vsockPort: Int) {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    val port: Int get() = server?.localPort ?: -1

    /** Binds 127.0.0.1:[preferred] (falls back to a free port) and starts accepting. */
    @Synchronized fun start(preferred: Int): Int {
        check(!running) { "already started" }
        val s = try {
            ServerSocket(preferred, 16, InetAddress.getByName("127.0.0.1"))
        } catch (_: Exception) {
            ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        }
        server = s
        running = true
        thread(name = "vsock-fwd-accept", isDaemon = true) {
            while (running) {
                val client = try { s.accept() } catch (_: Exception) { break }
                thread(name = "vsock-fwd", isDaemon = true) { serve(client) }
            }
        }
        return s.localPort
    }

    @Synchronized fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun serve(client: Socket) {
        val guest = try {
            Vsock.connect(cid, vsockPort)
        } catch (_: IOException) {
            try { client.close() } catch (_: Exception) {}
            return
        }
        // client -> guest: on local EOF stop writing but do NOT close the vsock
        // (half-close drops in-flight responses in the guest kernel); the
        // guest -> client pump below ends the connection when the guest closes.
        val up = thread(name = "vsock-fwd-up", isDaemon = true) {
            try {
                val buf = ByteArray(16384)
                val input = client.getInputStream()
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    guest.output.write(buf, 0, n)
                }
            } catch (_: Exception) {
            }
        }
        try {
            val buf = ByteArray(16384)
            while (true) {
                val n = guest.input.read(buf)
                if (n < 0) break
                client.getOutputStream().write(buf, 0, n)
                client.getOutputStream().flush()
            }
        } catch (_: Exception) {
        } finally {
            try { guest.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
            up.interrupt()
        }
    }
}