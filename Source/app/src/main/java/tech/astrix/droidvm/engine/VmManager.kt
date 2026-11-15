package tech.astrix.droidvm.engine

import tech.astrix.droidvm.i18n.tr
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.astrix.droidvm.data.Accel
import tech.astrix.droidvm.data.Firmware
import tech.astrix.droidvm.data.GuestArch
import tech.astrix.droidvm.data.ImageFormat
import tech.astrix.droidvm.data.Repository
import tech.astrix.droidvm.data.Snapshot
import tech.astrix.droidvm.data.VmConfig
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class VmState { STOPPED, STARTING, RUNNING, PAUSED, STOPPING, ERROR }

data class VmRuntime(val state: VmState, val message: String? = null, val startedAt: Long = 0)

/**
 * Owns all QEMU processes. Each VM runs as an independent child process of the app; the
 * foreground [VmService] keeps the app process alive while at least one VM is running.
 */
object VmManager {
    private const val TAG = "DroidVM.VmManager"
    private lateinit var app: Context
    lateinit var repo: Repository; private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _states = MutableStateFlow<Map<String, VmRuntime>>(emptyMap())
    val states: StateFlow<Map<String, VmRuntime>> = _states

    private class Running(val vm: VmConfig, val process: Process, val paths: VmPaths) {
        @Volatile var qmp: QmpClient? = null
        @Volatile var stopRequested = false
    }
    private val running = ConcurrentHashMap<String, Running>()

    fun init(context: Context, repository: Repository) {
        app = context.applicationContext
        repo = repository
    }

    fun state(id: String) = _states.value[id] ?: VmRuntime(VmState.STOPPED)
    fun isRunning(id: String) = running.containsKey(id)
    fun runningCount() = running.size
    fun paths(id: String) = VmPaths(repo.vmDir(id))

    private fun setState(id: String, s: VmRuntime) = _states.update { it + (id to s) }

    /** The qcow2 disk that holds internal snapshots / saved state (first writable qcow2 disk). */
    fun snapshotDisk(vm: VmConfig): File? =
        vm.disks.firstOrNull { it.format == ImageFormat.QCOW2 && !it.readOnly }?.let { File(it.path) }

    fun hasSavedState(vm: VmConfig): Boolean = runCatching {
        snapshotDisk(vm)?.let { d -> QemuImg.info(d).snapshots.any { it.tag == QemuCommand.AUTOSAVE_TAG } } ?: false
    }.getOrDefault(false)

    fun validate(vm: VmConfig): String? {
        if (!NativeEnv.ready) return NativeEnv.error ?: "Engine is not ready"
        vm.cdrom?.let { if (!File(it).exists()) return "Installation image not found: $it" }
        vm.disks.forEach { if (!File(it.path).exists()) return "Disk image not found: ${it.path}" }
        if (vm.kernelPath.isNotBlank() && !File(vm.kernelPath).isFile) return tr("Kernel file not found: {}").replace("{}", vm.kernelPath)
        if (vm.initrdPath.isNotBlank() && !File(vm.initrdPath).isFile) return tr("Initrd file not found: {}").replace("{}", vm.initrdPath)
        if (vm.arch == GuestArch.X86_64 && vm.firmware == tech.astrix.droidvm.data.Firmware.QBOOT && vm.kernelPath.isBlank())
            return tr("qboot can only start a Linux kernel. Set a kernel in \"Direct Linux boot\" or choose another firmware.")
        if (vm.cdrom == null && vm.disks.isEmpty() && vm.tftpDir.isNullOrBlank() && vm.kernelPath.isBlank()) return "The VM has no boot media. Attach an ISO or a disk image."
        tech.astrix.droidvm.data.NetConfig.validate(vm)?.let { return it }
        if (vm.arch == GuestArch.X86_64) {
            // i440FX: PIIX IDE = 2 buses x master/slave; Q35: 6 AHCI ports. QEMU refuses to start beyond that.
            val ide = vm.disks.count { it.iface == tech.astrix.droidvm.data.DiskInterface.SATA } + (if (vm.cdrom != null) 1 else 0)
            val legacy = vm.machine == tech.astrix.droidvm.data.MachineType.I440FX && !vm.firmware.isSecure
            val max = if (legacy) 4 else 6
            if (ide > max) return tr("Too many SATA/IDE devices ({}, maximum {}). Move disks to VirtIO, SCSI or NVMe.")
                .replaceFirst("{}", ide.toString()).replaceFirst("{}", max.toString())
        }
        tech.astrix.droidvm.data.NetConfig.busyPorts(vm).takeIf { it.isNotEmpty() }?.let {
            return tr("Port already in use on this device: {}").replace("{}", it.joinToString())
        }
        if (vm.accel == Accel.KVM && !HostCaps.kvmUsableFor(vm.arch))
            return "KVM is not available for ${vm.arch.label} on this device (/dev/kvm missing/inaccessible or architecture mismatch). Switch acceleration to TCG."
        return null
    }

    fun start(vm: VmConfig, resumeSaved: Boolean = false) {
        if (running.containsKey(vm.id)) return
        setState(vm.id, VmRuntime(VmState.STARTING, "Starting QEMU"))
        scope.launch {
            try {
                validate(vm)?.let { throw IllegalStateException(it) }
                val paths = paths(vm.id)
                paths.qmp.delete(); paths.vnc.delete(); paths.pid.delete(); paths.console.delete()
                val uefi = vm.arch == GuestArch.AARCH64 || vm.firmware.isUefi
                if (uefi && !paths.efiVars.exists()) {
                    val tpl = if (vm.arch == GuestArch.AARCH64) "edk2-arm-vars.fd" else "edk2-i386-vars.fd"
                    File(NativeEnv.share, tpl).copyTo(paths.efiVars)
                }
                vm.sharedFolder?.let { File(it).mkdirs() }
                val cmd = QemuCommand.build(
                    vm, NativeEnv.qemuSystem(vm.arch).absolutePath, NativeEnv.share.absolutePath, paths,
                    loadSnapshot = if (resumeSaved) QemuCommand.AUTOSAVE_TAG else null,
                )
                paths.log.writeText("# DroidVM ${java.util.Date()}\n# " + cmd.joinToString(" ") + "\n")
                val p = NativeEnv.processBuilder(cmd).redirectErrorStream(true).start()
                val r = Running(vm, p, paths)
                running[vm.id] = r
                ContextCompat.startForegroundService(app, Intent(app, VmService::class.java))
                Thread({ pumpLog(p, paths.log) }, "qemu-log-${vm.id}").apply { isDaemon = true; start() }
                Thread({ watch(r) }, "qemu-wait-${vm.id}").apply { isDaemon = true; start() }
                // wait for the QMP socket
                val deadline = System.currentTimeMillis() + 60_000
                var qmp: QmpClient? = null
                while (System.currentTimeMillis() < deadline && p.alive() && qmp == null) {
                    if (paths.qmp.exists()) qmp = runCatching {
                        QmpClient(LocalSocketConnection(paths.qmp.absolutePath)) { ev, _ -> onEvent(vm.id, ev) }
                            .also { it.handshake() }
                    }.getOrNull()
                    if (qmp == null) delay(200)
                }
                if (qmp == null) {
                    if (p.alive()) { p.destroy() }
                    return@launch
                }
                r.qmp = qmp
                setState(vm.id, VmRuntime(VmState.RUNNING, "QEMU ${qmp.version}", System.currentTimeMillis()))
                if (resumeSaved) runCatching { qmp.hmp("delvm ${QemuCommand.AUTOSAVE_TAG}") }
                VmService.refresh(app)
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
                running.remove(vm.id)?.process?.destroy()
                setState(vm.id, VmRuntime(VmState.ERROR, t.message ?: t.toString()))
                VmService.refresh(app)
            }
        }
    }

    private fun onEvent(id: String, ev: String) {
        when (ev) {
            "STOP" -> setState(id, state(id).copy(state = VmState.PAUSED, message = "Paused"))
            "RESUME" -> setState(id, state(id).copy(state = VmState.RUNNING, message = null))
            "SHUTDOWN", "POWERDOWN" -> if (ev == "SHUTDOWN") setState(id, state(id).copy(state = VmState.STOPPING, message = "Shutting down"))
        }
    }

    private fun pumpLog(p: Process, log: File) {
        runCatching {
            log.appendBytes(ByteArray(0))
            java.io.FileOutputStream(log, true).use { out ->
                val buf = ByteArray(8192)
                val ins = p.inputStream
                var written = 0L
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    // keep reading (QEMU must never block on a full pipe) but cap the log at 4 MB
                    if (written < 4L shl 20) { out.write(buf, 0, n); out.flush(); written += n }
                }
            }
        }
    }

    private fun watch(r: Running) {
        val code = runCatching { r.process.waitFor() }.getOrDefault(-1)
        running.remove(r.vm.id)
        runCatching { r.qmp?.close() }
        r.paths.qmp.delete(); r.paths.vnc.delete(); r.paths.console.delete()
        val tail = runCatching { r.paths.log.readLines().filterNot { it.startsWith("#") }.takeLast(6).joinToString("\n") }.getOrDefault("")
        val st = if (code == 0 || r.stopRequested) VmRuntime(VmState.STOPPED, if (code == 0) null else "Stopped")
                 else VmRuntime(VmState.ERROR, "QEMU exited with code $code\n$tail")
        setState(r.vm.id, st)
        VmService.refresh(app)
    }

    private fun qmp(id: String): QmpClient = running[id]?.qmp ?: throw IllegalStateException("VM is not running")

    suspend fun <T> io(block: () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching(block) }

    /** ACPI power button - the guest OS shuts down cleanly. */
    suspend fun powerDown(id: String) = io { qmp(id).execute("system_powerdown"); Unit }
    suspend fun reset(id: String) = io { qmp(id).execute("system_reset"); Unit }
    suspend fun pause(id: String) = io { qmp(id).execute("stop"); Unit }
    suspend fun resume(id: String) = io { qmp(id).execute("cont"); Unit }

    /** QEMU human monitor command (used by the console screen). */
    suspend fun monitor(id: String, cmd: String) = io { qmp(id).hmp(cmd, timeoutMs = 60_000) }

    /** Graceful stop of every VM: ACPI power button first, hard stop after [graceMs]. */
    fun stopAllGracefully(graceMs: Long = 20_000) {
        val ids = running.keys.toList()
        scope.launch {
            ids.forEach { id -> runCatching { running[id]?.qmp?.execute("system_powerdown", timeoutMs = 3000) } }
            val end = System.currentTimeMillis() + graceMs
            while (System.currentTimeMillis() < end && ids.any { running.containsKey(it) }) delay(500)
            ids.filter { running.containsKey(it) }.forEach { forceStop(it) }
        }
    }

    suspend fun forceStop(id: String) = io {
        val r = running[id] ?: return@io
        r.stopRequested = true
        runCatching { r.qmp?.execute("quit", timeoutMs = 3000) }
        Thread.sleep(500)
        if (!r.process.waitForMs(3000)) r.process.kill()
        Unit
    }

    /** Saves full machine state (RAM + devices + disks) into the qcow2 disk, then quits. */
    suspend fun saveStateAndQuit(id: String) = io {
        val r = running[id] ?: throw IllegalStateException("VM is not running")
        snapshotDisk(r.vm) ?: throw IllegalStateException("Saving state needs a writable QCOW2 disk")
        val q = qmp(id)
        q.execute("stop")
        val out = q.hmp("savevm ${QemuCommand.AUTOSAVE_TAG}")
        if (out.isNotBlank()) {
            q.execute("cont"); throw IllegalStateException(out.trim())
        }
        r.stopRequested = true
        runCatching { q.execute("quit", timeoutMs = 5000) }
        Unit
    }

    suspend fun listSnapshots(vm: VmConfig): Result<List<Snapshot>> = io {
        val d = snapshotDisk(vm) ?: throw IllegalStateException("Snapshots need a writable QCOW2 disk")
        QemuImg.info(d).snapshots
    }

    /** Live snapshot (RAM + disk) while running, disk-only snapshot when stopped. */
    suspend fun createSnapshot(vm: VmConfig, tag: String) = io {
        require(tag.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "Use letters, digits, '_', '-', '.' only" }
        if (isRunning(vm.id)) {
            val out = qmp(vm.id).hmp("savevm $tag")
            if (out.isNotBlank()) throw IllegalStateException(out.trim())
        } else {
            QemuImg.snapshotCreate(snapshotDisk(vm) ?: throw IllegalStateException("Snapshots need a writable QCOW2 disk"), tag)
        }
    }

    suspend fun restoreSnapshot(vm: VmConfig, tag: String) = io {
        if (isRunning(vm.id)) {
            val out = qmp(vm.id).hmp("loadvm $tag")
            if (out.isNotBlank()) throw IllegalStateException(out.trim())
            runCatching { qmp(vm.id).execute("cont") }
        } else {
            QemuImg.snapshotApply(snapshotDisk(vm) ?: throw IllegalStateException("No QCOW2 disk"), tag)
        }
    }

    suspend fun deleteSnapshot(vm: VmConfig, tag: String) = io {
        if (isRunning(vm.id)) {
            val out = qmp(vm.id).hmp("delvm $tag")
            if (out.isNotBlank()) throw IllegalStateException(out.trim())
        } else {
            QemuImg.snapshotDelete(snapshotDisk(vm) ?: throw IllegalStateException("No QCOW2 disk"), tag)
        }
    }

    /** Eject / change the CD-ROM medium of a running VM. */
    suspend fun ejectCdrom(id: String) = io {
        qmp(id).hmp("eject -f cd0"); Unit
    }

    fun stopAllForcibly() {
        running.values.forEach { r -> r.stopRequested = true; runCatching { r.process.destroy() } }
    }

    fun runningIds(): Set<String> = running.keys.toSet()
    fun runningName(id: String) = running[id]?.vm?.name
}
