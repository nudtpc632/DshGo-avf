package io.github.ccch1mneyyy.dsh_app.runtime.avf

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * The AVF guest bundle (runtime/build-vm-image.sh → runtime/out-vm/):
 * a read-only ext4 root disk, the `vm run` config and vm-manifest.json.
 * Installed under filesDir/runtime-avf/; the guest kernel comes from the
 * device's virt APEX (microdroid_kernel), not from the APK.
 */
class AvfImage(private val context: Context) {
    val baseDir = File(context.filesDir, "runtime-avf")
    val rootfs = File(baseDir, "rootfs.ext4")
    val configFile = File(baseDir, "vm-config.json")
    val manifestFile = File(baseDir, "vm-manifest.json")
    private val versionFile = File(baseDir, "image.version")

    fun installedVersion(): String? =
        if (rootfs.isFile && configFile.isFile && versionFile.isFile) versionFile.readText().trim().ifEmpty { null } else null

    fun manifest(): JSONObject? = try {
        if (manifestFile.isFile) JSONObject(manifestFile.readText()) else null
    } catch (_: Exception) {
        null
    }

    fun isCurrent(bundledVersion: String): Boolean = installedVersion() == bundledVersion

    /**
     * Copies the bundled image from assets/runtime-avf/ into app storage and
     * rewrites vm-config.json to point at the installed paths. The kernel is
     * located on the device (virt APEX); [onProgress] gets (bytesCopied, total).
     */
    fun install(onProgress: (Long, Long) -> Unit) {
        val assets = context.assets
        val names = assets.list(ASSET_DIR) ?: throw IOException("no bundled VM image")
        val manifestText = assets.open("$ASSET_DIR/vm-manifest.json").bufferedReader().use { it.readText() }
        val manifest = JSONObject(manifestText)
        val version = manifest.getString("runtimeVersion")
        val rootfsAsset = manifest.getString("rootfs")
        require(rootfsAsset in names) { "manifest rootfs missing from assets" }
        val expectedSha = manifest.getString("rootfsSha256")

        baseDir.mkdirs()
        val staging = File(baseDir, "staging").apply { deleteRecursively(); mkdirs() }
        val total = assets.openFd("$ASSET_DIR/$rootfsAsset").use { it.length }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        assets.open("$ASSET_DIR/$rootfsAsset").use { input ->
            File(staging, "rootfs.ext4").outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var copied = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                    copied += n
                    onProgress(copied, total)
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expectedSha, ignoreCase = true)) {
            staging.deleteRecursively()
            throw IOException("VM image checksum mismatch")
        }

        val kernel = findGuestKernel() ?: throw IOException("guest kernel not found on this device")
        val config = JSONObject()
        config.put("kernel", kernel.absolutePath)
        config.put("params", manifest.getJSONObject("boot").getString("params"))
        config.put("memory_mib", manifest.getJSONObject("boot").getInt("memoryMib"))
        config.put("platform_version", "1.0")
        val disk = JSONObject()
        disk.put("label", "rootfs")
        disk.put("path", File(baseDir, "rootfs.ext4").absolutePath)
        // Same shape as runtime/out-vm/vm-config.json (verified on-device).
        val partition = JSONObject().put("partitions", org.json.JSONArray().put(disk)).put("writable", false)
        config.put("disks", org.json.JSONArray().put(partition))

        File(staging, "vm-config.json").writeText(config.toString(2))
        File(staging, "vm-manifest.json").writeText(manifestText)
        File(staging, "image.version").writeText(version)
        // Swap the four files in; a half-written install never looks current.
        for (name in listOf("rootfs.ext4", "vm-config.json", "vm-manifest.json", "image.version")) {
            val from = File(staging, name)
            val to = File(baseDir, name)
            if (!from.renameTo(to)) {
                from.copyTo(to, overwrite = true)
                from.delete()
            }
        }
        staging.deleteRecursively()
    }

    fun reset() {
        baseDir.deleteRecursively()
    }

    companion object {
        const val ASSET_DIR = "runtime-avf"

        /** The device's microdroid kernel (virt APEX). Verified on GreatWall pd2508. */
        fun findGuestKernel(): File? = listOf(
            "/apex/com.android.virt/etc/fs/microdroid_kernel",
            "/apex/com.android.virt/etc/microdroid_kernel",
        ).map(::File).firstOrNull { it.isFile && it.canRead() }
    }
}