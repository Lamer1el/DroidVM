package tech.astrix.droidvm.engine

import android.content.Context
import android.os.Build
import android.util.Log
import tech.astrix.droidvm.BuildConfig
import tech.astrix.droidvm.data.GuestArch
import java.io.File

/**
 * Prepares the on-device runtime for the bundled QEMU:
 *  - executables + libraries are shipped as jniLibs (lib*.so) and executed ONLY from
 *    applicationInfo.nativeLibraryDir, where the package manager installs them read-only.
 *    Nothing executable is ever written, unpacked or chmod'ed in the app's data directory;
 *  - library dependencies were rewritten at build time (tools/fix_elf.py) to the real lib*.so
 *    file names, so the dynamic linker resolves them from nativeLibraryDir alone;
 *  - only non-executable data (firmware images, keymaps) is copied from assets into
 *    files/qemu/share, because QEMU reads it from a directory (passed as -L).
 */
object NativeEnv {
    private const val TAG = "DroidVM.NativeEnv"
    lateinit var nativeDir: File; private set
    lateinit var base: File; private set
    lateinit var share: File; private set
    lateinit var tmp: File; private set
    @Volatile var ready = false; private set
    @Volatile var error: String? = null; private set

    val supportedAbi: String? get() = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }

    fun init(ctx: Context, progress: (String) -> Unit = {}) {
        if (ready) return
        try {
            nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
            base = File(ctx.filesDir, "qemu").apply { mkdirs() }
            // v1.0/v1.1 used a symlink farm here; it is no longer needed
            File(base, "lib").takeIf { it.exists() }?.deleteRecursively()
            share = File(base, "share")
            tmp = File(ctx.cacheDir, "qemu-tmp").apply { mkdirs() }
            if (supportedAbi == null) {
                error = "This device ABI (${Build.SUPPORTED_ABIS.joinToString()}) is not supported. DroidVM requires arm64-v8a or x86_64."
                return
            }
            val stamp = File(base, ".stamp")
            val want = "${BuildConfig.VERSION_CODE}-${ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime}"
            if (!stamp.exists() || stamp.readText() != want || !share.exists()) {
                progress("Extracting firmware")
                share.deleteRecursively()
                copyAssetDir(ctx, "qemu/share", share)
                stamp.writeText(want)
            }
            val missing = GuestArch.entries.map { File(nativeDir, it.qemuBinary) }.filterNot { it.exists() } +
                listOfNotNull(File(nativeDir, "libqemu_img.so").takeUnless { it.exists() })
            if (missing.isNotEmpty()) {
                error = "QEMU binaries missing from the APK: ${missing.joinToString { it.name }}"
                return
            }
            ready = true
        } catch (t: Throwable) {
            Log.e(TAG, "init failed", t)
            error = "Engine initialisation failed: ${t.message}"
        }
    }

    private fun copyAssetDir(ctx: Context, path: String, dest: File) {
        val list = ctx.assets.list(path).orEmpty()
        if (list.isEmpty()) {
            dest.parentFile?.mkdirs()
            ctx.assets.open(path).use { i -> dest.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
            return
        }
        dest.mkdirs()
        list.forEach { copyAssetDir(ctx, "$path/$it", File(dest, it)) }
    }

    fun qemuSystem(arch: GuestArch) = File(nativeDir, arch.qemuBinary)
    val qemuImg: File get() = File(nativeDir, "libqemu_img.so")

    fun environment(): Map<String, String> = mapOf(
        "LD_LIBRARY_PATH" to nativeDir.absolutePath,
        "TMPDIR" to tmp.absolutePath,
        "HOME" to base.absolutePath,
        "LANG" to "C.UTF-8",
    )

    fun processBuilder(cmd: List<String>): ProcessBuilder =
        ProcessBuilder(cmd).also { pb ->
            pb.environment().putAll(environment())
            pb.directory(base)
        }

    /** Runs a short-lived helper (qemu-img, --version) and returns exit code + combined output. */
    fun run(cmd: List<String>, timeoutSec: Long = 600): Pair<Int, String> {
        val p = processBuilder(cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }.apply { start() }
        val finished = p.waitForMs(timeoutSec * 1000)
        if (!finished) { p.kill(); return -1 to "timeout" }
        reader.join(2000)
        return p.exitValue() to out.toString()
    }

    fun qemuVersion(): String = runCatching {
        val (code, out) = run(listOf(qemuSystem(GuestArch.X86_64).absolutePath, "--version"), 30)
        if (code == 0) out.lineSequence().first().trim() else "unavailable (exit $code): ${out.take(300)}"
    }.getOrElse { "unavailable: ${it.message}" }
}
