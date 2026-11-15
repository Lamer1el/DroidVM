package tech.astrix.droidvm.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class ImportProgress(
    val name: String,
    val copied: Long,
    val total: Long,
    val done: Boolean = false,
    val error: String? = null,
    val result: ImageEntry? = null,
)

/**
 * Imports ISO / IMG / QCOW2 / RAW files chosen through the Storage Access Framework.
 * QEMU needs a real file path, so the content is copied into app storage and then validated.
 */
object ImportManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _progress = MutableStateFlow<ImportProgress?>(null)
    val progress: StateFlow<ImportProgress?> = _progress
    private var job: Job? = null

    fun displayName(ctx: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "image"
        var size = -1L
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        return name to size
    }

    fun import(ctx: Context, uri: Uri, repo: Repository) {
        if (job?.isActive == true) return
        job = scope.launch {
            val (name, size) = displayName(ctx, uri)
            val id = repo.newId()
            val dir = File(repo.imagesDir, id).apply { mkdirs() }
            val safe = name.replace(Regex("[^A-Za-z0-9._+-]"), "_").ifBlank { "image" }
            val dest = File(dir, safe)
            _progress.value = ImportProgress(name, 0, size)
            try {
                val free = repo.imagesDir.usableSpace
                if (size > 0 && size > free - 64L * 1024 * 1024)
                    throw IllegalStateException("Not enough free space: need ${ImageInspector.human(size)}, available ${ImageInspector.human(free)}")
                ctx.contentResolver.openInputStream(uri)!!.use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20)
                        var copied = 0L; var last = 0L
                        while (isActive) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); copied += n
                            if (copied - last > 8L * 1024 * 1024) { last = copied; _progress.value = ImportProgress(name, copied, size) }
                        }
                    }
                }
                if (!isActive) throw InterruptedException("Import cancelled")
                val r = ImageInspector.inspect(dest, name)
                if (r.format == null) throw IllegalArgumentException(r.error ?: "Unsupported image")
                val entry = ImageEntry(id, name, dest.absolutePath, r.format, dest.length(), r.arch, r.details)
                repo.addImage(entry)
                _progress.value = ImportProgress(name, dest.length(), dest.length(), done = true, result = entry)
            } catch (t: Throwable) {
                dir.deleteRecursively()
                _progress.value = ImportProgress(name, 0, size, done = true, error = t.message ?: t.toString())
            }
        }
    }

    fun cancel() { job?.cancel() }
    fun dismiss() { _progress.value = null }
}
