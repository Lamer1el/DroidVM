package tech.astrix.droidvm.engine

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import tech.astrix.droidvm.data.GuestArch
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class LocalSocketConnection(path: String) : StreamConnection {
    private val socket = LocalSocket().apply {
        connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
    }
    override val input: InputStream = socket.inputStream
    override val output: OutputStream = socket.outputStream
    override fun close() { runCatching { socket.shutdownInput() }; runCatching { socket.close() } }
}

/**
 * Probes what the host device really supports. Nothing here is assumed: hardware
 * virtualisation is only offered when /dev/kvm exists, is accessible and the guest
 * architecture equals the host CPU architecture.
 */
object HostCaps {
    val hostArch: GuestArch? get() = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> GuestArch.AARCH64
        "x86_64" -> GuestArch.X86_64
        else -> null
    }

    fun kvmNodePresent() = File("/dev/kvm").exists()
    fun kvmAccessible() = File("/dev/kvm").let { it.exists() && it.canRead() && it.canWrite() }
    fun kvmUsableFor(arch: GuestArch) = kvmAccessible() && hostArch == arch

    @Volatile private var rootCache: Boolean? = null

    fun rootAvailable(refresh: Boolean = false): Boolean {
        if (!refresh) rootCache?.let { return it }
        val r = runSu("id").let { it.first == 0 && it.second.contains("uid=0") }
        rootCache = r
        return r
    }

    /** With root: make /dev/kvm accessible to the app (SELinux policy may still deny it). */
    fun enableKvmWithRoot(): Pair<Boolean, String> {
        if (!kvmNodePresent()) return false to "This kernel does not provide /dev/kvm."
        val (code, out) = runSu("chmod 666 /dev/kvm && ls -lZ /dev/kvm")
        return (code == 0 && kvmAccessible()) to out.trim()
    }

    private fun runSu(cmd: String): Pair<Int, String> = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        if (!p.waitForMs(8000)) { p.destroy(); -1 to "timeout" }
        else p.exitValue() to p.inputStream.bufferedReader().readText()
    } catch (e: Exception) { -1 to (e.message ?: "su not available") }

    fun totalRamMb(): Long = runCatching {
        File("/proc/meminfo").readLines().first { it.startsWith("MemTotal") }
            .filter { it.isDigit() }.toLong() / 1024
    }.getOrDefault(4096)

    fun cpuCount() = Runtime.getRuntime().availableProcessors()
}
