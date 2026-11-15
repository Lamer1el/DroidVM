package tech.astrix.droidvm.ui

import androidx.compose.material.icons.filled.Terminal
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.astrix.droidvm.DroidVmApp
import tech.astrix.droidvm.data.DiskInterface
import tech.astrix.droidvm.data.ImageFormat
import tech.astrix.droidvm.data.ImageInspector
import tech.astrix.droidvm.i18n.tr
import tech.astrix.droidvm.data.Snapshot
import tech.astrix.droidvm.data.VmConfig
import tech.astrix.droidvm.data.VmDisk
import tech.astrix.droidvm.display.DisplayActivity
import tech.astrix.droidvm.engine.QemuImg
import tech.astrix.droidvm.engine.VmManager
import tech.astrix.droidvm.engine.VmRuntime
import tech.astrix.droidvm.engine.VmState
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VmDetailScreen(id: String, onBack: () -> Unit, onEdit: () -> Unit) {
    val repo = DroidVmApp.repo
    val vms by repo.vms.collectAsStateWithLifecycle()
    val images by repo.images.collectAsStateWithLifecycle()
    val states by VmManager.states.collectAsStateWithLifecycle()
    val vm = vms.firstOrNull { it.id == id }
    if (vm == null) { LaunchedEffect(Unit) { onBack() }; return }
    val rt = states[id] ?: VmRuntime(VmState.STOPPED)
    val active = rt.state in setOf(VmState.RUNNING, VmState.PAUSED, VmState.STARTING, VmState.STOPPING)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var refresh by remember { mutableIntStateOf(0) }
    var hasSaved by remember { mutableStateOf(false) }
    var snapshots by remember { mutableStateOf<List<Snapshot>?>(null) }
    var snapError by remember { mutableStateOf<String?>(null) }
    var diskInfo by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var newSnapshot by remember { mutableStateOf<String?>(null) }
    var resizeDisk by remember { mutableStateOf<VmDisk?>(null) }
    var removeDisk by remember { mutableStateOf<VmDisk?>(null) }
    var addDisk by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }

    fun report(r: Result<*>, ok: String) { scope.launch { snack.showSnackbar(r.exceptionOrNull()?.message ?: ok) }; refresh++ }

    LaunchedEffect(vm, rt.state, refresh) {
        withContext(Dispatchers.IO) {
            hasSaved = !active && VmManager.hasSavedState(vm)
            diskInfo = vm.disks.associate { d ->
                d.id to runCatching { QemuImg.info(File(d.path)).let { "${it.format} • ${QemuImg.humanSize(it.virtualSize)} virtual • ${QemuImg.humanSize(it.actualSize)} used" } }
                    .getOrElse { "unavailable: ${it.message?.take(80)}" }
            }
        }
        val s = VmManager.listSnapshots(vm)
        snapshots = s.getOrNull()?.filter { it.tag != tech.astrix.droidvm.engine.QemuCommand.AUTOSAVE_TAG }
        snapError = s.exceptionOrNull()?.message
    }
    LaunchedEffect(showLog, rt.state) {
        while (showLog) {
            logText = withContext(Dispatchers.IO) { runCatching { VmManager.paths(id).log.readLines().takeLast(200).joinToString("\n") }.getOrDefault("(no log yet)") }
            delay(1500)
        }
    }

    Scaffold(
        snackbarHost = { LocalizedSnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text(vm.name) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = onEdit, enabled = !active) { Icon(Icons.Outlined.Edit, "Edit") }
                    IconButton(onClick = { confirmDelete = true }, enabled = !active) { Icon(Icons.Outlined.Delete, "Delete") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {

            SectionCard("Control", Icons.Filled.PowerSettingsNew, trailing = { StateChip(rt.state) }) {
                if (rt.state == VmState.ERROR && rt.message != null)
                    Text(rt.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!active) {
                        Button(onClick = { VmManager.start(vm); ctx.startActivity(DisplayActivity.afterStart(ctx, vm)) }) {
                            Icon(Icons.Filled.PlayArrow, null); Text(if (hasSaved) " Cold boot" else " Start")
                        }
                        if (hasSaved) FilledTonalButton(onClick = { VmManager.start(vm, resumeSaved = true); ctx.startActivity(DisplayActivity.afterStart(ctx, vm)) }) {
                            Icon(Icons.Outlined.History, null); Text(" Resume saved state")
                        }
                    } else {
                        Button(onClick = { ctx.startActivity(DisplayActivity.intent(ctx, id)) }) { Icon(Icons.Outlined.DesktopWindows, null); Text(" Open display") }
                        if (rt.state == VmState.PAUSED)
                            FilledTonalButton(onClick = { scope.launch { report(VmManager.resume(id), "Resumed") } }) { Icon(Icons.Filled.PlayArrow, null); Text(" Resume") }
                        else
                            FilledTonalButton(onClick = { scope.launch { report(VmManager.pause(id), "Paused") } }) { Icon(Icons.Filled.Pause, null); Text(" Pause") }
                        FilledTonalButton(onClick = { scope.launch { report(VmManager.powerDown(id), "ACPI shutdown sent") } }) { Icon(Icons.Filled.PowerSettingsNew, null); Text(" Shut down") }
                        OutlinedButton(onClick = { scope.launch { report(VmManager.reset(id), "Reset") } }) { Icon(Icons.Filled.RestartAlt, null); Text(" Reboot") }
                        OutlinedButton(onClick = { scope.launch { report(VmManager.saveStateAndQuit(id), "State saved") } }) { Icon(Icons.Outlined.Save, null); Text(" Save state & quit") }
                        OutlinedButton(onClick = { scope.launch { report(VmManager.forceStop(id), "Stopped") } }) { Text("Force stop") }
                    }
                    // Always available - also for stopped or failed VMs (shows the last serial output).
                    OutlinedButton(onClick = { ctx.startActivity(tech.astrix.droidvm.display.ConsoleActivity.intent(ctx, id)) }) {
                        Icon(Icons.Filled.Terminal, null); Text(" Console")
                    }
                }
                if (hasSaved) Hint("A saved state exists. \"Resume saved state\" continues exactly where you left off.")
            }

            SectionCard("Configuration", Icons.Outlined.Info) {
                InfoLine("Architecture", vm.arch.label)
                InfoLine("Memory / vCPU", "${vm.ramMb} MB / ${vm.cpus}")
                if (vm.arch.name != "AARCH64") InfoLine("Machine type", vm.machine.label)
                InfoLine("Firmware", if (vm.arch.name == "AARCH64") "UEFI (EDK2)" else vm.firmware.label)
                InfoLine("Display", "${vm.graphics.label}, ${vm.width}×${vm.height}")
                InfoLine("Sound", vm.audio.label)
                InfoLine("Network", if (vm.network) "${vm.nic.label}, NAT" + vm.portForwards.joinToString("") { " • ${it.proto} ${it.hostPort}→${it.guestPort}" } else "Off")
                if (vm.network) InfoLine("IP addresses", "${vm.netCidr} • ${tr("guest")} ${vm.guestIp} • ${tr("gateway")} ${vm.gatewayIp} • DNS ${vm.dnsIp}")
                val access = buildList {
                    vm.exposeVncPort?.let { add("VNC ${vm.bindAddress}:$it") }
                    vm.spicePort?.let { add("SPICE ${vm.bindAddress}:$it") }
                    vm.telnetSerialPort?.let { add("Telnet ${vm.bindAddress}:$it") }
                    if (!vm.tftpDir.isNullOrBlank()) add("TFTP/PXE")
                }
                if (access.isNotEmpty()) InfoLine("Remote access", access.joinToString(" • "))
                InfoLine("Acceleration", vm.accel.label)
            }

            SectionCard("CD-ROM", Icons.Outlined.Album) {
                Text(vm.cdrom?.let { File(it).name } ?: "Empty", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dropdown("Insert image", "Choose…", images.filter { it.format.isOptical }, { it.name }) { e ->
                        repo.saveVm(vm.copy(cdrom = e.path)); scope.launch { snack.showSnackbar(if (active) "Takes effect on next start" else "Inserted") }
                    }
                }
                if (vm.cdrom != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        if (active) scope.launch { report(VmManager.ejectCdrom(id), "Ejected") }
                        repo.saveVm(vm.copy(cdrom = null, bootFromCd = false))
                    }) { Text("Eject") }
                    if (!active) OutlinedButton(onClick = { repo.saveVm(vm.copy(bootFromCd = !vm.bootFromCd)) }) {
                        Text(if (vm.bootFromCd) "Boot from disk first" else "Boot from CD first")
                    }
                }
                Hint("After installing Linux, eject the ISO so the VM boots from its disk.")
            }

            SectionCard("Virtual disks", Icons.Outlined.Storage) {
                if (vm.disks.isEmpty()) Text("No disks", style = MaterialTheme.typography.bodyMedium)
                vm.disks.forEachIndexed { i, d ->
                    Column {
                        Text("${i + 1}. ${d.label.ifBlank { File(d.path).name }}" + if (d.readOnly) " (read-only)" else "", style = MaterialTheme.typography.titleSmall)
                        Text("${d.iface.label} • ${diskInfo[d.id] ?: "…"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(d.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!active) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AssistChip(onClick = { resizeDisk = d }, label = { Text("Resize") })
                            if (d.format != ImageFormat.QCOW2) AssistChip(onClick = {
                                scope.launch {
                                    snack.showSnackbar("Converting to QCOW2…")
                                    val r = VmManager.io {
                                        val dst = File(repo.disksDir(vm.id), File(d.path).nameWithoutExtension + "-${repo.newId()}.qcow2")
                                        QemuImg.convertToQcow2(File(d.path), d.format.qemuFormat, dst)
                                        repo.saveVm(vm.copy(disks = vm.disks.map { if (it.id == d.id) it.copy(path = dst.absolutePath, format = ImageFormat.QCOW2) else it }))
                                    }
                                    report(r, "Converted to QCOW2 (original kept)")
                                }
                            }, label = { Text("Convert to QCOW2") })
                            AssistChip(onClick = { repo.saveVm(vm.copy(disks = vm.disks.map { if (it.id == d.id) it.copy(iface = DiskInterface.entries[(d.iface.ordinal + 1) % DiskInterface.entries.size]) else it })) },
                                label = { Text("Bus: ${d.iface.label}") })
                            AssistChip(onClick = { removeDisk = d }, label = { Text(tr("Remove…")) })
                        }
                    }
                    if (i < vm.disks.lastIndex) HorizontalDivider()
                }
                if (!active) FilledTonalButton(onClick = { addDisk = true }) { Text("Add disk") }
                Hint("Detaching keeps the file. \"Delete file\" erases a disk created for this VM. Disk files of a VM are deleted together with the VM; imported images stay in the library.")
            }

            SectionCard("Snapshots", Icons.Outlined.History) {
                when {
                    snapError != null -> Hint(snapError!!)
                    snapshots == null -> Hint("Loading…")
                    snapshots!!.isEmpty() -> Hint("No snapshots yet.")
                    else -> snapshots!!.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.tag, style = MaterialTheme.typography.titleSmall)
                                Text("${s.date} • ${s.vmSize}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { scope.launch { report(VmManager.restoreSnapshot(vm, s.tag), "Restored ${s.tag}") } }) { Text("Restore") }
                            IconButton(onClick = { scope.launch { report(VmManager.deleteSnapshot(vm, s.tag), "Deleted") } }) { Icon(Icons.Outlined.Delete, "Delete") }
                        }
                    }
                }
                if (snapError == null) FilledTonalButton(onClick = { newSnapshot = "snap-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()) }) {
                    Text("Create snapshot")
                }
                Hint(if (active) "Live snapshot: includes RAM and device state (not possible while a shared folder is mounted in the guest)." else "Offline snapshot: disk contents only.")
            }

            vm.sharedFolder?.let { sf ->
                SectionCard("Shared folder", Icons.Outlined.Folder) {
                    InfoLine("Android", sf)
                    SelectionContainer {
                        Text("sudo mkdir -p /mnt/droidvm\nsudo mount -t 9p -o trans=virtio,version=9p2000.L,msize=512000 droidvm /mnt/droidvm",
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                    Hint("Requires a guest kernel with 9p/virtio (all mainstream distributions).")
                }
            }

            SectionCard("QEMU log", Icons.Outlined.Terminal, trailing = { TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide" else "Show") } }) {
                if (showLog) SelectionContainer {
                    Text(logText, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()))
                }
            }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${vm.name}?") },
        text = {
            val own = vm.disks.filter { File(it.path).absolutePath.startsWith(repo.vmDir(vm.id).absolutePath) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("The VM configuration and the disks created for it will be deleted. Imported images stay in the library."))
                if (own.isNotEmpty()) {
                    Text(tr("Files that will be deleted:"), style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(own.joinToString("\n") { it.path }, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { confirmDelete = false; repo.deleteVm(vm.id); onBack() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
    newSnapshot?.let { tag ->
        var name by remember { mutableStateOf(tag) }
        AlertDialog(
            onDismissRequest = { newSnapshot = null },
            title = { Text("New snapshot") },
            text = { OutlinedTextField(name, { name = it.filter { c -> c.isLetterOrDigit() || c in "-_." } }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
                newSnapshot = null
                scope.launch { snack.showSnackbar("Creating snapshot…"); report(VmManager.createSnapshot(vm, name), "Snapshot created") }
            }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { newSnapshot = null }) { Text("Cancel") } },
        )
    }
    removeDisk?.let { d ->
        val f = File(d.path)
        val owned = f.absolutePath.startsWith(repo.vmDir(vm.id).absolutePath)
        val usedElsewhere = repo.vms.value.any { o -> o.id != vm.id && o.disks.any { it.path == d.path } }
        AlertDialog(
            onDismissRequest = { removeDisk = null },
            title = { Text(tr("Remove disk {}?").replace("{}", d.label.ifBlank { f.name })) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("File path:"), style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(f.absolutePath, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                Text(tr("Size on storage: {}").replace("{}", if (f.exists()) ImageInspector.human(f.length()) else tr("file missing")),
                    style = MaterialTheme.typography.bodySmall)
                Hint(if (owned) "\"Delete file\" permanently erases this disk and all data on it. \"Detach\" keeps the file."
                     else "This file belongs to the image library and is not deleted here. Delete it in the Images tab.")
            } },
            confirmButton = {
                Row {
                    if (owned && !usedElsewhere) TextButton(onClick = {
                        removeDisk = null
                        repo.saveVm(vm.copy(disks = vm.disks.filterNot { it.id == d.id }))
                        val ok = f.delete()
                        scope.launch { snack.showSnackbar(if (ok) tr("Deleted: {}").replace("{}", f.absolutePath) else tr("Could not delete {}").replace("{}", f.absolutePath)) }
                    }) { Text(tr("Delete file"), color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { removeDisk = null; repo.saveVm(vm.copy(disks = vm.disks.filterNot { it.id == d.id })) }) { Text(tr("Detach")) }
                }
            },
            dismissButton = { TextButton(onClick = { removeDisk = null }) { Text(tr("Cancel")) } },
        )
    }
    resizeDisk?.let { d ->
        var gb by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { resizeDisk = null },
            title = { Text("Resize disk") },
            text = { Column {
                OutlinedTextField(gb, { gb = it.filter(Char::isDigit) }, singleLine = true, label = { Text("New size, GB") })
                Hint("Only growing is supported. Afterwards enlarge the partition inside Linux (e.g. growpart / resize2fs or GParted).")
            } },
            confirmButton = { TextButton(enabled = (gb.toIntOrNull() ?: 0) > 0, onClick = {
                resizeDisk = null
                scope.launch { report(VmManager.io { QemuImg.resize(File(d.path), d.format.qemuFormat, gb.toInt()) }, "Resized to $gb GB") }
            }) { Text("Resize") } },
            dismissButton = { TextButton(onClick = { resizeDisk = null }) { Text("Cancel") } },
        )
    }
    if (addDisk) {
        var gb by remember { mutableStateOf("20") }
        AlertDialog(
            onDismissRequest = { addDisk = false },
            title = { Text("Add disk") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(gb, { gb = it.filter(Char::isDigit) }, singleLine = true, label = { Text("New empty QCOW2, GB") })
                TextButton(enabled = (gb.toIntOrNull() ?: 0) > 0, onClick = {
                    addDisk = false
                    scope.launch {
                        val r = VmManager.io {
                            val f = File(repo.disksDir(vm.id), "disk-${repo.newId()}.qcow2")
                            QemuImg.create(f, gb.toInt())
                            repo.saveVm(vm.copy(disks = vm.disks + VmDisk(repo.newId(), f.absolutePath, ImageFormat.QCOW2, DiskInterface.VIRTIO, label = "Disk ($gb GB)")))
                        }
                        report(r, "Disk created")
                    }
                }) { Text("Create new disk") }
                HorizontalDivider()
                Text("Or attach an image from the library:", style = MaterialTheme.typography.labelLarge)
                images.filter { !it.format.isOptical }.forEach { e ->
                    TextButton(onClick = {
                        addDisk = false
                        repo.saveVm(vm.copy(disks = vm.disks + VmDisk(repo.newId(), e.path, e.format, DiskInterface.VIRTIO, label = e.name)))
                    }) { Text("${e.name} (${e.format.label})") }
                }
            } },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { addDisk = false }) { Text("Cancel") } },
        )
    }
}

@Suppress("unused") private fun VmConfig.short() = "$name ${arch.label}"
