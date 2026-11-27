package io.github.ccch1mneyyy.dsh_app.runtime.avf

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * AVF backend lifecycle, mirroring DshProcess's contract for RuntimeController:
 * start() boots the guest, waits for vm-ctl `ready`, forwards loopback → vsock,
 * verifies dsh web answers, then reports onReady(baseUrl, token). stop() saves
 * DSH_HOME over vm-ctl (the guest root is read-only; state lives on tmpfs) and
 * tears the VM down; the saved state is restored on the next start.
 *
 * Failure surface is deliberately the same as DshProcess: onExit(code) for an
 * unexpected VM death or a failed start (with [startupError] set), onReady for
 * success. RuntimeController's strikes/safe-mode logic applies unchanged.
 */
class AvfProcess(
    context: Context,
    private val prefs: SharedPreferences,
    private val onLog: (String) -> Unit,
) {
    fun interface ReadyListener { fun onReady(source: AvfProcess, baseUrl: String, token: String) }
    fun interface ExitListener { fun onExit(source: AvfProcess, code: Int) }

    private val image = AvfImage(context)
    private val vm = AvfVm(image)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "avf-start").apply { isDaemon = true } }
    private val saver = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "avf-save").apply { isDaemon = true } }
    private val stopping = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    @Volatile private var forwarder: VsockForwarder? = null
    @Volatile private var egress: EgressProxy? = null
    @Volatile private var readyListener: ReadyListener? = null
    @Volatile private var exitListener: ExitListener? = null
    @Volatile private var baseUrl: String? = null
    @Volatile private var token: String? = null
    @Volatile private var saveTask: ScheduledFuture<*>? = null
    @Volatile private var lastSnapshot: String? = null
    @Volatile var startupError: String? = null
        private set
    /** Extra guest files restored at start (e.g. the phone bridge.json); not persisted. */
    @Volatile var extraRestoreFiles: Map<String, String> = emptyMap()

    val isRunning: Boolean get() = vm.isAlive
    val currentBaseUrl: String? get() = baseUrl
    val currentToken: String? get() = token
    val imageInstalled: Boolean get() = image.installedVersion() != null
    val installedVersion: String? get() = image.installedVersion()

    fun setListeners(ready: ReadyListener?, exit: ExitListener?) {
        readyListener = ready
        exitListener = exit
    }

    fun install(onProgress: (Long, Long) -> Unit) {
        image.install(onProgress)
        onLog("[dsh-app] AVF image installed: ${image.installedVersion()}")
    }

    fun reset() {
        stop()
        image.reset()
        prefs.edit().remove(K_STATE).apply()
    }

    /** Boots the guest and reports readiness asynchronously (like DshProcess.start). */
    fun start() {
        check(imageInstalled) { "AVF image not installed" }
        check(!vm.isAlive) { "AVF VM already running" }
        stopping.set(false)
        startupError = null
        val gen = generation.incrementAndGet()
        baseUrl = null
        token = null
        // Seed the dedup baseline from the persisted snapshot so the first
        // periodic save after a restore is a no-op when nothing changed.
        lastSnapshot = prefs.getString(K_STATE, null)
        executor.execute { runStart(gen) }
    }

    private fun runStart(gen: Long) {
        try {
            vm.start(onLog)
            // Watch for an unexpected VM death while running. Armed only after
            // vm.start(): awaitExit() on a not-yet-spawned process returns at once.
            Thread({
                val code = vm.awaitExit()
                if (!stopping.get() && gen == generation.get() && baseUrl != null) {
                    onLog("[dsh-app] AVF VM exited unexpectedly (code $code)")
                    saveTask?.cancel(false)
                    saveTask = null
                    cleanupForwarder()
                    baseUrl = null
                    token = null
                    exitListener?.onExit(this, code)
                }
            }, "avf-exit-watch").apply { isDaemon = true }.start()
            val cid = awaitCid(gen) ?: throw IOException("VM did not report a CID")
            onLog("[dsh-app] AVF VM up, cid=$cid")
            val status = awaitReady(cid, gen)
            // Restore only once vm-ctl answers: right after CID the guest is
            // still booting and the vsock connect would be reset.
            restoreState(cid)
            startEgress()
            val httpPort = forwardHttp(cid)
            val url = "http://127.0.0.1:$httpPort"
            if (!canConnect(httpPort)) throw IOException("dsh web not reachable through forwarder")
            if (stopping.get() || gen != generation.get()) return
            baseUrl = url
            token = status.token
            onLog("[dsh-app] AVF dsh web ready at $url")
            startPeriodicSave(gen)
            readyListener?.onReady(this, url, status.token ?: "")
        } catch (t: Throwable) {
            if (stopping.get() || gen != generation.get()) return
            startupError = t.message ?: t.javaClass.simpleName
            onLog("[dsh-app] AVF start failed: $startupError")
            saveTask?.cancel(false)
            saveTask = null
            runCatching { vm.stop() }
            cleanupForwarder()
            exitListener?.onExit(this, 1)
        }
    }

    private fun awaitCid(gen: Long): Int? {
        val deadline = System.currentTimeMillis() + CID_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (stopping.get() || gen != generation.get()) return null
            if (!vm.isAlive) return null
            vm.findCid()?.let { return it }
            Thread.sleep(500)
        }
        return null
    }

    private fun awaitReady(cid: Int, gen: Long): VmCtl.Status {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        var lastError: String? = null
        while (System.currentTimeMillis() < deadline) {
            if (stopping.get() || gen != generation.get()) throw IOException("cancelled")
            if (!vm.isAlive) throw IOException("VM exited during boot")
            try {
                val status = VmCtl.status(cid)
                if (status.ready && !status.token.isNullOrEmpty()) return status
                lastError = "not ready yet"
            } catch (t: IOException) {
                lastError = t.message
            }
            Thread.sleep(1000)
        }
        throw IOException("dsh web did not become ready (${lastError ?: "timeout"})")
    }

    /** The guest has no virtio-net; its egress proxy tunnels out through here. */
    private fun startEgress() {
        val proxy = EgressProxy(onLog)
        proxy.start()
        egress = proxy
    }

    private fun forwardHttp(cid: Int): Int {
        val fwd = VsockForwarder(cid, WEB_PORT)
        val port = fwd.start(preferred = WEB_PORT)
        forwarder = fwd
        onLog("[dsh-app] forwarding 127.0.0.1:$port -> vsock cid=$cid:$WEB_PORT")
        return port
    }

    private fun restoreState(cid: Int) {
        val map = HashMap<String, String>()
        prefs.getString(K_STATE, null)?.let { saved ->
            val files = JSONObject(saved)
            files.keys().forEach { map[it] = files.getString(it) }
        }
        map.putAll(extraRestoreFiles)
        if (map.isEmpty()) return
        try {
            val restored = VmCtl.restore(cid, map)
            onLog("[dsh-app] restored $restored file(s) into the guest")
        } catch (t: Exception) {
            // Keep the snapshot so a later restart can retry; the guest starts fresh.
            onLog("[dsh-app] state restore skipped: ${t.message}")
        }
    }

    /** Saves DSH_HOME before teardown; best-effort (a dead guest has nothing to save). */
    private fun saveState() {
        val cid = vm.cid.takeIf { it > 0 } ?: return
        try {
            val files = VmCtl.save(cid)
            val obj = JSONObject()
            files.forEach { (k, v) -> obj.put(k, v) }
            val json = obj.toString()
            if (json == lastSnapshot) return
            // commit(): the caller may be racing process death (stop/force-stop);
            // the snapshot must be on disk before the VM goes away.
            prefs.edit().putString(K_STATE, json).commit()
            lastSnapshot = json
            onLog("[dsh-app] saved ${files.size} DSH_HOME file(s)")
        } catch (t: Exception) {
            onLog("[dsh-app] state save skipped: ${t.message}")
        }
    }

    /**
     * DSH_HOME lives on guest tmpfs: a force-stop, system kill or VM crash loses
     * everything not yet snapshotted, and stop() is the only other save point.
     * Persist a snapshot periodically while running so a custom provider (or any
     * settings change) survives an ungraceful restart. Deduped: an unchanged
     * snapshot is not rewritten.
     */
    private fun startPeriodicSave(gen: Long) {
        saveTask?.cancel(false)
        saveTask = saver.scheduleWithFixedDelay({
            if (stopping.get() || gen != generation.get() || !vm.isAlive) return@scheduleWithFixedDelay
            saveState()
        }, SAVE_INTERVAL_MS, SAVE_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        if (!vm.isAlive && forwarder == null) return
        stopping.set(true)
        generation.incrementAndGet()
        saveTask?.cancel(false)
        saveTask = null
        saveState()
        cleanupForwarder()
        runCatching { vm.stop() }
        baseUrl = null
        token = null
    }

    /** Waits for the VM process to exit (reset uses this before deleting the image). */
    fun awaitExit(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (vm.isAlive && System.currentTimeMillis() < deadline) Thread.sleep(200)
        return !vm.isAlive
    }

    private fun cleanupForwarder() {
        forwarder?.stop()
        forwarder = null
        egress?.stop()
        egress = null
    }

    private fun canConnect(port: Int): Boolean {
        return try {
            val conn = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            conn.disconnect()
            code in 200..499
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val WEB_PORT = 3080
        private const val K_STATE = "avf_dsh_home_state"
        private const val CID_TIMEOUT_MS = 30_000L
        private const val READY_TIMEOUT_MS = 180_000L
        private const val SAVE_INTERVAL_MS = 15_000L
    }
}