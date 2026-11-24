package io.github.ccch1mneyyy.dsh_app.runtime.avf

import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * vm-ctl client: one JSON request line per vsock connection to guest port 3081
 * (bridged to 127.0.0.1:13081), one JSON response line, then the guest closes.
 * Never half-closes the write side (vsock.transportContract).
 */
object VmCtl {
    data class Status(val ready: Boolean, val token: String?, val baseUrl: String?)

    fun status(cid: Int, port: Int = CTL_PORT): Status {
        val resp = request(cid, port, JSONObject().put("cmd", "status"))
        if (!resp.optBoolean("ok")) throw IOException("vm-ctl status failed: ${resp.optString("error")}")
        return Status(
            ready = resp.optBoolean("ready"),
            token = if (resp.isNull("token")) null else resp.optString("token").ifEmpty { null },
            baseUrl = if (resp.isNull("baseUrl")) null else resp.optString("baseUrl").ifEmpty { null },
        )
    }

    /** DSH_HOME regular files as base64, keyed by relative path. */
    fun save(cid: Int, port: Int = CTL_PORT): Map<String, String> {
        val resp = request(cid, port, JSONObject().put("cmd", "save"))
        if (!resp.optBoolean("ok")) throw IOException("vm-ctl save failed: ${resp.optString("error")}")
        val files = resp.getJSONObject("files")
        return files.keys().asSequence().associateWith { files.getString(it) }
    }

    fun restore(cid: Int, files: Map<String, String>, port: Int = CTL_PORT): Int {
        val payload = JSONObject()
        files.forEach { (k, v) -> payload.put(k, v) }
        val resp = request(cid, port, JSONObject().put("cmd", "restore").put("files", payload))
        if (!resp.optBoolean("ok")) throw IOException("vm-ctl restore failed: ${resp.optString("error")}")
        return resp.optInt("restored")
    }

    private fun request(cid: Int, port: Int, body: JSONObject): JSONObject {
        Vsock.connect(cid, port).use { conn ->
            conn.output.write((body.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
            conn.output.flush()
            // Read to EOF: the guest closes after responding. No SHUT_WR.
            val text = conn.input.readBytes().toString(StandardCharsets.UTF_8).trim()
            if (text.isEmpty()) throw IOException("vm-ctl: empty response")
            return JSONObject(text.lineSequence().first())
        }
    }

    const val CTL_PORT = 3081
}