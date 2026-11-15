package tech.astrix.droidvm.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Persists VM definitions, the image library and app settings as JSON files in the
 * app's private storage. Layout:
 *   files/vms/<id>/config.json, disks/, efivars.fd, qemu.log, *.sock
 *   files/images/<id>/<file>     imported installation media / disk images
 */
class Repository(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    val root: File = context.filesDir
    val vmsDir = File(root, "vms").apply { mkdirs() }
    val imagesDir = File(root, "images").apply { mkdirs() }
    private val imagesIndex = File(root, "images.json")
    private val settingsFile = File(root, "settings.json")

    private val _vms = MutableStateFlow<List<VmConfig>>(emptyList())
    val vms: StateFlow<List<VmConfig>> = _vms
    private val _images = MutableStateFlow<List<ImageEntry>>(emptyList())
    val images: StateFlow<List<ImageEntry>> = _images
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings

    init { reload() }

    fun reload() {
        _vms.value = vmsDir.listFiles().orEmpty().mapNotNull { dir ->
            runCatching { json.decodeFromString<VmConfig>(File(dir, "config.json").readText()) }.getOrNull()
        }.sortedBy { it.createdAt }
        _images.value = runCatching { json.decodeFromString<List<ImageEntry>>(imagesIndex.readText()) }
            .getOrDefault(emptyList()).filter { File(it.path).exists() }
        _settings.value = runCatching { json.decodeFromString<AppSettings>(settingsFile.readText()) }
            .getOrDefault(AppSettings())
    }

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(8)

    fun vmDir(id: String) = File(vmsDir, id).apply { mkdirs() }
    fun disksDir(id: String) = File(vmDir(id), "disks").apply { mkdirs() }
    fun vm(id: String): VmConfig? = _vms.value.firstOrNull { it.id == id }

    @Synchronized
    fun saveVm(vm: VmConfig) {
        val f = File(vmDir(vm.id), "config.json")
        val tmp = File(f.parentFile, "config.json.tmp")
        tmp.writeText(json.encodeToString(VmConfig.serializer(), vm))
        tmp.renameTo(f)
        _vms.value = (_vms.value.filterNot { it.id == vm.id } + vm).sortedBy { it.createdAt }
    }

    @Synchronized
    fun deleteVm(id: String) {
        File(vmsDir, id).deleteRecursively()
        _vms.value = _vms.value.filterNot { it.id == id }
    }

    @Synchronized
    fun saveImages(list: List<ImageEntry>) {
        imagesIndex.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ImageEntry.serializer()), list))
        _images.value = list
    }

    fun addImage(e: ImageEntry) = saveImages(_images.value.filterNot { it.id == e.id } + e)

    fun deleteImage(id: String) {
        val e = _images.value.firstOrNull { it.id == id } ?: return
        File(e.path).parentFile?.takeIf { it.parentFile == imagesDir }?.deleteRecursively() ?: File(e.path).delete()
        saveImages(_images.value.filterNot { it.id == id })
    }

    fun saveSettings(s: AppSettings) {
        settingsFile.writeText(json.encodeToString(AppSettings.serializer(), s))
        _settings.value = s
    }
}
