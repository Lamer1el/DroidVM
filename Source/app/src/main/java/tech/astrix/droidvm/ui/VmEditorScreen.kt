package tech.astrix.droidvm.ui

import androidx.compose.material3.TextButton
import tech.astrix.droidvm.data.VmLinkMode
import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.astrix.droidvm.DroidVmApp
import tech.astrix.droidvm.data.Accel
import tech.astrix.droidvm.data.AudioDevice
import tech.astrix.droidvm.data.DiskInterface
import tech.astrix.droidvm.data.Firmware
import tech.astrix.droidvm.data.GraphicsDevice
import tech.astrix.droidvm.data.GuestArch
import tech.astrix.droidvm.data.ImageEntry
import tech.astrix.droidvm.data.ImageFormat
import tech.astrix.droidvm.data.MachineType
import tech.astrix.droidvm.data.NetConfig
import tech.astrix.droidvm.data.NicModel
import tech.astrix.droidvm.i18n.tr
import tech.astrix.droidvm.data.PortForward
import tech.astrix.droidvm.data.VmConfig
import tech.astrix.droidvm.data.VmDisk
import tech.astrix.droidvm.engine.HostCaps
import tech.astrix.droidvm.engine.QemuImg
import tech.astrix.droidvm.engine.VmManager
import java.io.File

private val RESOLUTIONS = listOf(800 to 600, 1024 to 768, 1280 to 720, 1280 to 800, 1366 to 768, 1600 to 900, 1920 to 1080, 2560 to 1440)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VmEditorScreen(id: String, onDone: (String?) -> Unit) {
    val repo = DroidVmApp.repo
    val images by repo.images.collectAsStateWithLifecycle()
    val existing = remember(id) { if (id == "new") null else repo.vm(id) }
    val isNew = existing == null
    val maxRam = remember { (HostCaps.totalRamMb() * 3 / 4).toInt().coerceIn(512, 32768) }
    val maxCpu = remember { HostCaps.cpuCount().coerceAtLeast(1) }
    var vm by remember {
        mutableStateOf(existing ?: VmConfig(
            id = repo.newId(), name = "Linux VM",
            arch = HostCaps.hostArch ?: GuestArch.X86_64,
            ramMb = minOf(2048, maxRam), cpus = minOf(2, maxCpu),
        ).let { if (it.arch == GuestArch.AARCH64) it.copy(firmware = Firmware.UEFI, graphics = GraphicsDevice.VIRTIO_GPU) else it })
    }
    var bootImage by remember { mutableStateOf<ImageEntry?>(null) }
    var newDiskGb by remember { mutableStateOf(if (isNew) 20 else 0) }
    var useImageAsDisk by remember { mutableStateOf(true) }
    var forwards by remember { mutableStateOf(vm.portForwards.joinToString(", ") { (if (it.proto == "udp") "udp:" else "") + "${it.hostPort}:${it.guestPort}" }) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (isNew) "New virtual machine" else "Edit ${existing?.name}") },
            navigationIcon = { IconButton(onClick = { onDone(null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("General", Icons.Outlined.Tune) {
                OutlinedTextField(vm.name, { vm = vm.copy(name = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SwitchRow("Open console instead of display on start", "For servers and text-only systems: shows the serial console (ttyS0) first", vm.openConsoleOnStart) {
                    vm = vm.copy(openConsoleOnStart = it)
                }
                Text("Guest architecture", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    GuestArch.entries.forEachIndexed { i, a ->
                        SegmentedButton(selected = vm.arch == a, onClick = {
                            vm = vm.copy(arch = a,
                                firmware = if (a == GuestArch.AARCH64) Firmware.UEFI else vm.firmware,
                                graphics = if (a == GuestArch.AARCH64 && vm.graphics == GraphicsDevice.CIRRUS) GraphicsDevice.VIRTIO_GPU else vm.graphics,
                                accel = if (HostCaps.kvmUsableFor(a)) vm.accel else Accel.TCG)
                        }, shape = SegmentedButtonDefaults.itemShape(i, GuestArch.entries.size)) { Text(a.label) }
                    }
                }
                val hostNote = if (HostCaps.hostArch == vm.arch) "Same architecture as this device." else
                    "Different from the device CPU - full software emulation, expect slower performance."
                Hint(hostNote)
                if (vm.arch == GuestArch.X86_64) {
                    Dropdown("Machine type (chipset)", vm.machine.label, MachineType.entries, { it.label }) { vm = vm.copy(machine = it) }
                    if (vm.machine == MachineType.I440FX) Hint("Legacy PC chipset for old operating systems (PIIX IDE, PCI). Up to 4 IDE devices incl. CD-ROM.")
                }
            }

            SectionCard("Processor & memory", Icons.Outlined.Memory) {
                SliderRow("Memory", vm.ramMb, 256..maxRam, 256, { if (it >= 1024) "%.1f GB".format(it / 1024f) else "$it MB" }) { vm = vm.copy(ramMb = it) }
                SliderRow("Virtual CPUs", vm.cpus, 1..maxCpu) { vm = vm.copy(cpus = it) }
                Hint("Device: ${HostCaps.totalRamMb() / 1024} GB RAM, $maxCpu cores. Leave memory for Android itself.")
            }

            SectionCard("Boot media & disks", Icons.Outlined.Storage) {
                if (isNew) {
                    Dropdown("Installation / boot image", bootImage?.let { "${it.name} (${it.format.label})" } ?: "None",
                        listOf<ImageEntry?>(null) + images, { it?.let { e -> "${e.name} • ${e.format.label}" + (e.detectedArch?.let { a -> " • ${a.label}" } ?: "") } ?: "None" }) { e ->
                        bootImage = e
                        e?.detectedArch?.let { a -> if (a != vm.arch) error = "Note: this image looks like ${a.label}, the VM is ${vm.arch.label}." else error = null }
                    }
                    if (images.isEmpty()) Hint("Import ISO / IMG / QCOW2 / RAW files in the Images tab first.")
                    bootImage?.let { e ->
                        if (!e.format.isOptical) SwitchRow("Use image as the VM's system disk",
                            "Off: the image is attached read-only", useImageAsDisk) { useImageAsDisk = it }
                    }
                    SliderRow("New virtual disk (QCOW2)", newDiskGb, 0..256, 4, { if (it == 0) "none" else "$it GB" }) { newDiskGb = it }
                    Hint("QCOW2 disks grow on demand and support snapshots and saved state. Installing from an ISO needs a disk.")
                } else {
                    Hint("Disks and CD-ROM are managed on the VM's details screen.")
                }
                Text("Firmware", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Firmware.entries.forEach { f ->
                        FilterChip(selected = vm.firmware == f || (vm.arch == GuestArch.AARCH64 && f == Firmware.UEFI),
                            enabled = vm.arch == GuestArch.X86_64, onClick = { vm = vm.copy(firmware = f) }, label = { Text(f.label) })
                    }
                }
                if (vm.firmware.isSecure && vm.arch == GuestArch.X86_64)
                    Hint("Secure Boot firmware runs on Q35 with SMM. Keys are not enrolled by default - enroll them in the UEFI setup (Esc at boot) to enforce Secure Boot.")
                if (vm.arch == GuestArch.X86_64) when (vm.firmware) {
                    Firmware.UEFI_IA32, Firmware.UEFI_IA32_SECURE -> Hint("For systems that only have a 32-bit EFI loader (bootia32.efi), e.g. some tablet builds and 32-bit Windows 8/10. The CPU stays 64-bit capable.")
                    Firmware.BIOS_128K -> Hint("Smaller SeaBIOS build for very old operating systems; use with the i440FX chipset.")
                    Firmware.QBOOT -> Hint("qboot starts in a fraction of a second but cannot boot from CD or disk boot sectors: set a kernel below in \"Direct Linux boot\".")
                    else -> {}
                }
                Text("Direct Linux boot (optional)", style = MaterialTheme.typography.labelLarge)
                TextRow("Kernel file (bzImage / vmlinuz / Image)", vm.kernelPath) { vm = vm.copy(kernelPath = it.trim()) }
                if (vm.kernelPath.isNotBlank()) {
                    TextRow("Initrd file (optional)", vm.initrdPath) { vm = vm.copy(initrdPath = it.trim()) }
                    TextRow("Kernel command line", vm.kernelCmdline) { vm = vm.copy(kernelCmdline = it) }
                    if (vm.kernelCmdline.isBlank()) TextButton(onClick = { vm = vm.copy(kernelCmdline = "console=ttyS0 console=tty0 root=/dev/vda1 rw") }) {
                        Text("Insert example: console=ttyS0 root=/dev/vda1")
                    }
                }
                Hint("QEMU loads the kernel itself, no boot loader is needed. Files outside the app folder need All files access, e.g. /sdcard/DroidVM/vmlinuz.")
                SwitchRow("Boot from CD-ROM first", null, vm.bootFromCd) { vm = vm.copy(bootFromCd = it) }
            }

            SectionCard("Display", Icons.Outlined.Monitor) {
                Dropdown("Resolution", "${vm.width} × ${vm.height}", RESOLUTIONS, { "${it.first} × ${it.second}" }) {
                    vm = vm.copy(width = it.first, height = it.second)
                }
                Dropdown("Graphics adapter", vm.graphics.label, GraphicsDevice.entries.filter { vm.arch == GuestArch.X86_64 || (it != GraphicsDevice.CIRRUS && it != GraphicsDevice.VMWARE && it != GraphicsDevice.QXL) }, { it.label }) {
                    vm = vm.copy(graphics = it)
                }
                Hint("The resolution is offered to the guest as its preferred mode (EDID). The guest may still pick another mode; DroidVM follows it automatically.")
            }

            SectionCard("Sound", Icons.Outlined.VolumeUp) {
                Dropdown("Audio device", vm.audio.label, AudioDevice.entries.filter { vm.arch == GuestArch.X86_64 || (it != AudioDevice.AC97 && it != AudioDevice.SB16) }, { it.label }) {
                    vm = vm.copy(audio = it)
                }
            }

            SectionCard("Network", Icons.Outlined.Wifi) {
                SwitchRow("Network access (NAT)", "Guest reaches the internet through Android; no root needed", vm.network) { vm = vm.copy(network = it) }
                if (vm.network) {
                    Dropdown("Network card", vm.nic.label, NicModel.entries.filter { vm.arch == GuestArch.X86_64 || !it.legacyOnly }, { it.label }) { vm = vm.copy(nic = it) }
                    Text("IP addresses", style = MaterialTheme.typography.labelLarge)
                    TextRow("Guest network (CIDR)", vm.netCidr) { vm = vm.copy(netCidr = it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) { TextRow("Guest IP (DHCP)", vm.guestIp) { vm = vm.copy(guestIp = it) } }
                        Column(Modifier.weight(1f)) { TextRow("Gateway IP", vm.gatewayIp) { vm = vm.copy(gatewayIp = it) } }
                    }
                    TextRow("DNS server IP", vm.dnsIp) { vm = vm.copy(dnsIp = it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("10.0.2.0/24", "192.168.76.0/24", "172.16.10.0/24").forEach { net ->
                            FilterChip(selected = vm.netCidr == net, onClick = { vm = withSubnet(vm, net) }, label = { Text(net) })
                        }
                    }
                    Hint("The guest gets its IP from the built-in DHCP server. Changing the network moves gateway, DNS and guest IP into it.")
                    SwitchRow("IPv6", null, vm.ipv6) { vm = vm.copy(ipv6 = it) }
                    if (vm.ipv6) TextRow("IPv6 prefix (optional, e.g. fd00::/64)", vm.ipv6Prefix) { vm = vm.copy(ipv6Prefix = it.trim()) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) { TextRow("Guest hostname (DHCP)", vm.guestHostname) { vm = vm.copy(guestHostname = it.trim()) } }
                        Column(Modifier.weight(1f)) { TextRow("DNS search domain", vm.dnsSearch) { vm = vm.copy(dnsSearch = it.trim()) } }
                    }
                    SwitchRow("Isolated (no internet)", "Guest can only reach the virtual gateway and forwarded ports", vm.restrictNet) { vm = vm.copy(restrictNet = it) }
                    Dropdown("Listen address on Android", vm.bindAddress, listOf("127.0.0.1", "0.0.0.0"),
                        { if (it == "0.0.0.0") tr("0.0.0.0 - all networks (LAN)") else tr("127.0.0.1 - this device only") }) { vm = vm.copy(bindAddress = it) }
                    if (vm.bindAddress == "0.0.0.0") Text(tr("Warning: forwarded ports, VNC, SPICE and Telnet become reachable from your Wi-Fi network without a password."),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(forwards, { forwards = it }, label = { Text(tr("Port forwards host:guest (TCP/UDP)")) },
                        supportingText = { Text(tr("e.g. 2222:22, 8080:80, udp:5353:53")) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
            }

            SectionCard("Protocols & remote access", Icons.Outlined.Lan) {
                Hint("Built-in display uses VNC (RFB) over a private socket. Optional servers below listen on the address chosen in Network.")
                PortRow("VNC server port (RFB, 5900-5999)", vm.exposeVncPort) { vm = vm.copy(exposeVncPort = it?.takeIf { p -> p in 5900..5999 }) }
                PortRow("SPICE server port (e.g. 5930)", vm.spicePort) { vm = vm.copy(spicePort = it) }
                if (vm.spicePort != null) Hint("Connect with aSPICE / remote-viewer: spice://127.0.0.1:${vm.spicePort}. Install spice-vdagent in the guest for clipboard; QXL graphics recommended.")
                PortRow("Serial console over Telnet (e.g. 2323)", vm.telnetSerialPort) { vm = vm.copy(telnetSerialPort = it) }
                if (vm.telnetSerialPort != null) {
                    SwitchRow("Raw TCP instead of Telnet", "For nc / socat / PuTTY raw connections", vm.serialRawTcp) { vm = vm.copy(serialRawTcp = it) }
                    Hint(if (vm.serialRawTcp) "nc 127.0.0.1 ${vm.telnetSerialPort} - add console=ttyS0 to the guest kernel command line to get a login prompt."
                         else "telnet 127.0.0.1 ${vm.telnetSerialPort} - add console=ttyS0 to the guest kernel command line to get a login prompt.")
                    Hint("The built-in console and the network port share the same serial line.")
                }
                PortRow("VNC over WebSocket port (noVNC, e.g. 5700)", vm.websocketPort) { vm = vm.copy(websocketPort = it) }
                if (vm.websocketPort != null) Hint("Open noVNC in a browser and connect to ws://127.0.0.1:${vm.websocketPort}")
                Text("VM-to-VM network link (TCP)", style = MaterialTheme.typography.labelLarge)
                Dropdown("Link mode", vm.vmLink.label, VmLinkMode.entries, { it.label }) { vm = vm.copy(vmLink = it) }
                if (vm.vmLink != VmLinkMode.OFF) {
                    if (vm.vmLink == VmLinkMode.CONNECT) TextRow("Address of the other VM's device", vm.vmLinkHost) { vm = vm.copy(vmLinkHost = it.trim()) }
                    PortRow("Link port", vm.vmLinkPort) { vm = vm.copy(vmLinkPort = it ?: 24800) }
                    Hint("Adds a second network card connected to another DroidVM/QEMU VM (same device or LAN). Give both guests static IPs on that card, e.g. 192.168.100.1 and 192.168.100.2.")
                }
                Hint("SSH: forward a port to guest port 22 (default 2222:22), then ssh -p 2222 user@127.0.0.1.")
                val tftpOn = vm.tftpDir != null
                SwitchRow("TFTP / PXE network boot (legacy BOOTP)", "Serve a folder over TFTP inside the guest network", tftpOn) {
                    vm = vm.copy(tftpDir = if (it) File(Environment.getExternalStorageDirectory(), "DroidVM/tftp").absolutePath else null)
                }
                vm.tftpDir?.let { dir ->
                    TextRow("TFTP folder", dir) { vm = vm.copy(tftpDir = it) }
                    TextRow("Boot file (e.g. pxelinux.0)", vm.tftpBootFile) { vm = vm.copy(tftpBootFile = it) }
                }
            }

            SectionCard("Shared folder", Icons.Outlined.Folder) {
                val default = File(Environment.getExternalStorageDirectory(), "DroidVM/Shared/${vm.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}").absolutePath
                SwitchRow("Share an Android folder with Linux", "VirtFS / 9p, mount tag \"droidvm\"", vm.sharedFolder != null) {
                    vm = vm.copy(sharedFolder = if (it) default else null)
                }
                vm.sharedFolder?.let { path ->
                    OutlinedTextField(path, { vm = vm.copy(sharedFolder = it) }, label = { Text("Android path") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Hint("In Linux: sudo mount -t 9p -o trans=virtio,version=9p2000.L droidvm /mnt\nFolders outside the app need \"All files access\" (Settings tab).")
                }
            }

            SectionCard("Acceleration", Icons.Outlined.Speed) {
                val kvmOk = HostCaps.kvmUsableFor(vm.arch)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Accel.entries.forEach { a ->
                        FilterChip(selected = vm.accel == a, enabled = a == Accel.TCG || kvmOk,
                            onClick = { vm = vm.copy(accel = a) }, label = { Text(a.label) })
                    }
                }
                Hint(if (kvmOk) "KVM is accessible on this device." else
                    "KVM unavailable: " + (if (!HostCaps.kvmNodePresent()) "the kernel exposes no /dev/kvm" else if (!HostCaps.kvmAccessible()) "/dev/kvm is not accessible (root may help, see Settings)" else "guest architecture differs from the device CPU") + ". Software emulation (TCG) is used.")
                SliderRow("TCG translation cache", vm.tbSizeMb, 64..1024, 64, { "$it MB" }) { vm = vm.copy(tbSizeMb = it) }
                SwitchRow("RTC in local time", "Enable for guests that expect local time", vm.localTimeRtc) { vm = vm.copy(localTimeRtc = it) }
                OutlinedTextField(vm.extraArgs, { vm = vm.copy(extraArgs = it) }, label = { Text("Extra QEMU arguments (advanced)") }, modifier = Modifier.fillMaxWidth())
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(enabled = !busy && vm.name.isNotBlank(), onClick = {
                    busy = true; error = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                var cfg = vm.copy(portForwards = parseForwards(forwards))
                                NetConfig.validate(cfg)?.let { throw IllegalArgumentException(it) }
                                if (isNew) {
                                    val disks = ArrayList<VmDisk>()
                                    bootImage?.let { e ->
                                        if (e.format.isOptical) cfg = cfg.copy(cdrom = e.path)
                                        else disks += VmDisk(repo.newId(), e.path, e.format,
                                            if (cfg.arch == GuestArch.X86_64) DiskInterface.VIRTIO else DiskInterface.VIRTIO,
                                            readOnly = !useImageAsDisk, label = e.name)
                                    }
                                    if (newDiskGb > 0) {
                                        val f = File(repo.disksDir(cfg.id), "disk${disks.size}.qcow2")
                                        QemuImg.create(f, newDiskGb)
                                        disks += VmDisk(repo.newId(), f.absolutePath, ImageFormat.QCOW2, DiskInterface.VIRTIO, label = "System disk ($newDiskGb GB)")
                                    }
                                    cfg = cfg.copy(disks = disks, bootFromCd = cfg.cdrom != null && cfg.bootFromCd)
                                }
                                repo.saveVm(cfg)
                                cfg
                            }
                        }
                        busy = false
                        result.onSuccess { onDone(it.id) }.onFailure { error = it.message }
                    }
                }) {
                    if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).then(Modifier), strokeWidth = 2.dp)
                    Text(if (isNew) "Create VM" else "Save")
                }
            }
            if (!isNew && VmManager.isRunning(vm.id)) Hint("Changes apply on the next start of the VM.")
        }
    }
}

private fun parseForwards(s: String): List<PortForward> = s.split(',', ' ', ';').mapNotNull { t ->
    var p = t.trim().lowercase().split(':')
    var proto = "tcp"
    if (p.size == 3 && (p[0] == "tcp" || p[0] == "udp")) { proto = p[0]; p = p.drop(1) }
    if (p.size == 2) {
        val h = p[0].toIntOrNull(); val g = p[1].toIntOrNull()
        if (h != null && g != null && h in 1024..65535 && g in 1..65535) PortForward(proto, h, g) else null
    } else null
}

/** Moves gateway (.2), DNS (.3) and guest (.15) into a new /24-style network. */
private fun withSubnet(vm: VmConfig, cidr: String): VmConfig {
    val base = cidr.substringBefore('/').substringBeforeLast('.')
    return vm.copy(netCidr = cidr, gatewayIp = "$base.2", dnsIp = "$base.3", guestIp = "$base.15")
}

@Composable
private fun TextRow(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(tr(label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun PortRow(label: String, value: Int?, onChange: (Int?) -> Unit) {
    var text by remember(value == null) { mutableStateOf(value?.toString() ?: "") }
    OutlinedTextField(text, { t -> text = t.filter { it.isDigit() }.take(5); onChange(text.toIntOrNull()) },
        label = { Text(tr(label)) }, placeholder = { Text(tr("off")) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Dropdown(label: String, value: String, options: List<T>, text: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(text(o)) }, onClick = { onSelect(o); open = false }) }
        }
    }
}

@Suppress("unused")
private val placeholderIcon = Icons.Outlined.Album
