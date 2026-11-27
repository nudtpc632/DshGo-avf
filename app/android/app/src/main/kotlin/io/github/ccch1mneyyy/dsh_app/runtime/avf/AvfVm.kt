package io.github.ccch1mneyyy.dsh_app.runtime.avf

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.io.IOException

/**
 * Owns the AVF guest process. On this device the public VirtualMachineManager
 * API is gated off (aconfig custom_vm flags disabled), so the launch goes
 * through the shell `vm` tool (/apex/com.android.virt/bin/vm), the same path
 * verified on-device in docs/design/avf-backend.md §6.
 *
 * createVm needs MANAGE_VIRTUAL_MACHINE and the custom (non-Microdroid) image
 * needs USE_CUSTOM_VIRTUAL_MACHINE (both development-level; declared in the
 * manifest and granted once via `adb shell pm grant`). `vm list` needs the
 * signature-level DEBUG_VIRTUAL_MACHINE which an app cannot hold, so the cid
 * is parsed from `vm run`'s own stdout ("... with CID N, state is ...") and
 * stopping kills our own `vm run` process — it is the VM's requester, and
 * virtmgr tears the guest down when the requester exits.
 */
class AvfVm(private val image: AvfImage) {
    @Volatile private var process: Process? = null
    @Volatile var cid: Int = -1
        private set

    val isAlive: Boolean get() = process?.isAlive == true

    /** Starts `vm run` with the installed config; returns once the process spawned. */
    @Synchronized fun start(onLog: (String) -> Unit) {
        check(process?.isAlive != true) { "VM already running" }
        check(image.installedVersion() != null) { "VM image not installed" }
        val console = File(image.baseDir, "vm-console.log")
        val argv = listOf(
            VM_TOOL, "run", "--name", NAME,
            "--debug", "full",
            "--console", console.absolutePath,
            image.configFile.absolutePath,
        )
        onLog("[dsh-app] launching AVF VM: ${argv.joinToString(" ")}")
        val builder = ProcessBuilder(argv).directory(image.baseDir).redirectErrorStream(true)
        val p = builder.start()
        process = p
        cid = -1
        // Drain vm run's own output into the runtime log; the guest console goes to the file.
        // The first line carries the cid: `Created VM from "<config>" with CID N, state is ...`.
        Thread({
            try {
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (cid < 0) {
                            CID_LINE.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { cid = it }
                        }
                        onLog("[vm] $line")
                    }
                }
            } catch (_: Exception) {
            }
        }, "avf-vm-out").apply { isDaemon = true }.start()
    }

    /** The cid parsed from `vm run` stdout; null until virtmgr reports it. */
    fun findCid(): Int? = cid.takeIf { it > 0 && isAlive }

    /** Stops the VM: our `vm run` process is the requester; virtmgr tears the guest down with it. */
    @Synchronized fun stop() {
        val p = process
        if (p != null && p.isAlive) {
            p.destroy()
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        }
        process = null
        cid = -1
    }

    /** Waits for the vm run process to exit; returns its code (or -1). */
    fun awaitExit(): Int {
        val p = process ?: return -1
        return try { p.waitFor() } catch (_: InterruptedException) { -1 }
    }

    companion object {
        const val NAME = "dsh-go"
        private const val VM_TOOL = "/apex/com.android.virt/bin/vm"
        private const val MANAGE_VM_PERMISSION = "android.permission.MANAGE_VIRTUAL_MACHINE"
        private const val CUSTOM_VM_PERMISSION = "android.permission.USE_CUSTOM_VIRTUAL_MACHINE"
        private val CID_LINE = Regex("""with CID (\d+)""")

        /**
         * Whether this device can run the AVF backend: the shell VM tool and a
         * guest kernel are present, and both development-level VM permissions
         * have been granted (`adb shell pm grant` once per install):
         * MANAGE_VIRTUAL_MACHINE for createVm, USE_CUSTOM_VIRTUAL_MACHINE for
         * the non-Microdroid image. Without them createVm fails with
         * EX_SECURITY, so the switch stays off rather than failing at start.
         * The JNI library is loaded lazily by [Vsock.available] at connect
         * time; a missing lib surfaces as a start failure, not a hidden switch.
         */
        fun supported(context: Context): Boolean =
            File(VM_TOOL).canExecute() &&
                AvfImage.findGuestKernel() != null &&
                context.checkSelfPermission(MANAGE_VM_PERMISSION) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(CUSTOM_VM_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }
}