package tech.astrix.droidvm.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import tech.astrix.droidvm.data.Snapshot
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class QemuImgException(msg: String) : Exception(msg)

/** Thin wrapper around the bundled qemu-img binary. */
object QemuImg {
    private val json = Json { ignoreUnknownKeys = true }

    private fun img(vararg args: String, timeoutSec: Long = 3600): String {
        val (code, out) = NativeEnv.run(listOf(NativeEnv.qemuImg.absolutePath) + args, timeoutSec)
        if (code != 0) throw QemuImgException(out.trim().ifEmpty { "qemu-img exited with $code" })
        return out
    }

    fun create(file: File, sizeGb: Int) {
        file.parentFile?.mkdirs()
        img("create", "-f", "qcow2", "-o", "cluster_size=65536,lazy_refcounts=on", file.absolutePath, "${sizeGb}G")
    }

    fun resize(file: File, format: String, newSizeGb: Int) {
        img("resize", "-f", format, file.absolutePath, "${newSizeGb}G")
    }

    fun convertToQcow2(src: File, srcFormat: String, dst: File) {
        img("convert", "-f", srcFormat, "-O", "qcow2", "-o", "cluster_size=65536", src.absolutePath, dst.absolutePath, timeoutSec = 6 * 3600)
    }

    data class Info(val format: String, val virtualSize: Long, val actualSize: Long, val snapshots: List<Snapshot>)

    fun info(file: File): Info {
        val o = json.parseToJsonElement(img("info", "-U", "--output=json", file.absolutePath)).jsonObject
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val snaps = o["snapshots"]?.jsonArray?.map { s ->
            val so = s.jsonObject
            val clock = (so["vm-clock-sec"]?.jsonPrimitive?.longOrNull ?: 0)
            Snapshot(
                id = so["id"]?.jsonPrimitive?.contentOrNull ?: "",
                tag = so["name"]?.jsonPrimitive?.contentOrNull ?: "",
                vmSize = humanSize(so["vm-state-size"]?.jsonPrimitive?.longOrNull ?: 0),
                date = fmt.format(Date((so["date-sec"]?.jsonPrimitive?.longOrNull ?: 0) * 1000)),
                vmClock = String.format(Locale.US, "%02d:%02d:%02d", clock / 3600, clock / 60 % 60, clock % 60),
            )
        } ?: emptyList()
        return Info(
            o["format"]?.jsonPrimitive?.contentOrNull ?: "?",
            o["virtual-size"]?.jsonPrimitive?.longOrNull ?: 0,
            o["actual-size"]?.jsonPrimitive?.longOrNull ?: file.length(),
            snaps,
        )
    }

    fun snapshotCreate(file: File, tag: String) { img("snapshot", "-c", tag, file.absolutePath) }
    fun snapshotApply(file: File, tag: String) { img("snapshot", "-a", tag, file.absolutePath) }
    fun snapshotDelete(file: File, tag: String) { img("snapshot", "-d", tag, file.absolutePath) }

    fun humanSize(b: Long) = tech.astrix.droidvm.data.ImageInspector.human(b)
}
