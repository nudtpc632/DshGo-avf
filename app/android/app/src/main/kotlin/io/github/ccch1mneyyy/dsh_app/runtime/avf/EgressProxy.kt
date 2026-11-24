package io.github.ccch1mneyyy.dsh_app.runtime.avf

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * App-side egress for the AVF guest: the custom-VM path has no virtio-net, so
 * the guest's egress bridge (vsock-tcp-bridge -c 13082 3082) tunnels outbound
 * requests over vsock:[EGRESS_PORT] to this listener, which dials the real
 * target with the App's network. Speaks the same minimal proxy protocol as the
 * guest side: CONNECT host:port (HTTPS) and absolute-form HTTP requests.
 *
 * Transport contract (vm-manifest.json vsock.transportContract): the vsock side
 * is never half-closed; pumps run until either side fully closes.
 */
class EgressProxy(private val log: (String) -> Unit = {}) {
    @Volatile private var listener: VsockListener? = null
    @Volatile private var running = false

    @Synchronized fun start() {
        check(!running) { "already started" }
        val l = Vsock.listen(EGRESS_PORT)
        listener = l
        running = true
        thread(name = "avf-egress-accept", isDaemon = true) {
            while (running) {
                val guest = try { l.accept() } catch (_: Exception) { break }
                thread(name = "avf-egress", isDaemon = true) { serve(guest) }
            }
        }
        log("[dsh-app] egress listener on vsock:$EGRESS_PORT")
    }

    @Synchronized fun stop() {
        running = false
        try { listener?.close() } catch (_: Exception) {}
        listener = null
    }

    private fun serve(guest: VsockConnection) {
        val head = StringBuilder()
        val input = guest.input
        // Read the request head byte-wise: a CONNECT head is tiny and the TLS
        // ClientHello that follows must stay in the stream for the pump.
        while (head.length < 65536) {
            val b = try { input.read() } catch (_: Exception) { -1 }
            if (b < 0) { close(guest); return }
            head.append(b.toChar())
            if (head.endsWith("\r\n\r\n")) break
        }
        val headStr = head.toString()
        val eol = headStr.indexOf("\r\n")
        val requestLine = (if (eol >= 0) headStr.substring(0, eol) else headStr).split(" ")
        if (requestLine.size < 2) { close(guest); return }
        val method = requestLine[0]
        val target = requestLine[1]
        val host: String
        val port: Int
        if (method == "CONNECT") {
            val hp = target.split(":")
            host = hp[0]
            port = hp.getOrNull(1)?.toIntOrNull() ?: 443
        } else {
            val url = try { java.net.URL(target) } catch (_: Exception) { close(guest); return }
            host = url.host
            port = if (url.port > 0) url.port else 80
        }
        val remote = Socket()
        try {
            remote.connect(InetSocketAddress(host, port), 10_000)
        } catch (_: IOException) {
            try { guest.output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray()) } catch (_: Exception) {}
            close(guest)
            return
        }
        remote.tcpNoDelay = true
        if (method == "CONNECT") {
            guest.output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            guest.output.flush()
        } else {
            remote.getOutputStream().write(headStr.toByteArray(Charsets.ISO_8859_1))
        }
        pump(guest, guest.input, remote, remote.getOutputStream())
        pump(remote, remote.getInputStream(), guest, guest.output)
    }

    private fun pump(from: Closeable, input: InputStream, to: Closeable, output: OutputStream) {
        thread(name = "avf-egress-pump", isDaemon = true) {
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
        const val EGRESS_PORT = 3082
    }
}