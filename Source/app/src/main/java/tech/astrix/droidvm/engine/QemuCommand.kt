package tech.astrix.droidvm.engine

import tech.astrix.droidvm.data.Accel
import tech.astrix.droidvm.data.AudioDevice
import tech.astrix.droidvm.data.DiskInterface
import tech.astrix.droidvm.data.Firmware
import tech.astrix.droidvm.data.GraphicsDevice
import tech.astrix.droidvm.data.GuestArch
import tech.astrix.droidvm.data.MachineType
import tech.astrix.droidvm.data.VmConfig
import java.io.File

/** Paths a running VM needs. Unix-socket paths must stay below 108 bytes. */
data class VmPaths(val dir: File) {
    val qmp = File(dir, "qmp.sock")
    val vnc = File(dir, "vnc.sock")
    val log = File(dir, "qemu.log")
    val efiVars = File(dir, "efivars.fd")
    val pid = File(dir, "qemu.pid")
    /** Serial console (ttyS0) for the built-in console screen. */
    val console = File(dir, "console.sock")
    val serialLog = File(dir, "serial.log")
}

/**
 * Builds a complete QEMU command line from a [VmConfig]. Pure function; unit-tested on the JVM.
 */
object QemuCommand {

    const val AUTOSAVE_TAG = "droidvm-autosave"

    fun build(
        vm: VmConfig,
        qemuBinary: String,
        dataDir: String,
        paths: VmPaths,
        loadSnapshot: String? = null,
    ): List<String> {
        val a = ArrayList<String>()
        a += qemuBinary
        a += listOf("-L", dataDir, "-name", "${sanitize(vm.name)},process=droidvm-${vm.id}")
        a += listOf("-nodefaults", "-no-user-config")
        val kvm = vm.accel == Accel.KVM

        // --- machine / cpu / accel ---
        when (vm.arch) {
            GuestArch.X86_64 -> {
                val secure = vm.firmware.isSecure
                // Secure Boot needs SMM, which EDK2 only supports on Q35
                val m = if (secure) MachineType.Q35 else vm.machine
                a += listOf("-machine", "${m.qemu},vmport=off,hpet=off" + if (secure) ",smm=on" else "")
                if (secure) a += listOf("-global", "driver=cfi.pflash01,property=secure,value=on")
                a += listOf("-cpu", if (kvm) "host" else "max")
            }
            GuestArch.AARCH64 -> {
                a += listOf("-machine", "virt,gic-version=" + (if (kvm) "host" else "3") + ",highmem=on")
                // pauth-impdef makes pointer authentication far cheaper under TCG
                a += listOf("-cpu", if (kvm) "host" else "max,pauth-impdef=on")
            }
        }
        a += listOf("-accel", if (kvm) "kvm" else "tcg,thread=multi,tb-size=${vm.tbSizeMb}")
        a += listOf("-smp", "${vm.cpus}", "-m", "${vm.ramMb}M")
        a += listOf("-rtc", if (vm.localTimeRtc) "base=localtime" else "base=utc")

        // --- firmware ---
        val uefi = vm.arch == GuestArch.AARCH64 || vm.firmware.isUefi
        if (uefi) {
            val code = if (vm.arch == GuestArch.AARCH64) "edk2-aarch64-code.fd" else vm.firmware.uefiCode ?: "edk2-x86_64-code.fd"
            a += listOf("-drive", "if=pflash,format=raw,unit=0,readonly=on,file=$dataDir/$code")
            a += listOf("-drive", "if=pflash,format=raw,unit=1,file=${paths.efiVars.absolutePath}")
        }
        if (vm.arch == GuestArch.X86_64) vm.firmware.biosFile?.let { a += listOf("-bios", "$dataDir/$it") }
        // --- direct Linux boot (kernel + optional initrd + command line, no boot loader) ---
        if (vm.kernelPath.isNotBlank()) {
            a += listOf("-kernel", vm.kernelPath)
            if (vm.initrdPath.isNotBlank()) a += listOf("-initrd", vm.initrdPath)
            if (vm.kernelCmdline.isNotBlank()) a += listOf("-append", vm.kernelCmdline)
        }

        // --- graphics (W x H is advertised to the guest through EDID) ---
        val w = vm.width; val h = vm.height
        when (vm.graphics) {
            GraphicsDevice.VGA ->
                if (vm.arch == GuestArch.X86_64) a += listOf("-device", "VGA,vgamem_mb=64,edid=on,xres=$w,yres=$h")
                else a += listOf("-device", "bochs-display,edid=on,xres=$w,yres=$h")
            GraphicsDevice.VIRTIO_GPU ->
                a += listOf("-device", (if (vm.arch == GuestArch.X86_64) "virtio-vga" else "virtio-gpu-pci") + ",edid=on,xres=$w,yres=$h")
            GraphicsDevice.RAMFB -> a += listOf("-device", "ramfb")
            GraphicsDevice.CIRRUS -> a += listOf("-device", if (vm.arch == GuestArch.X86_64) "cirrus-vga" else "bochs-display")
            GraphicsDevice.VMWARE -> a += listOf("-device", if (vm.arch == GuestArch.X86_64) "vmware-svga,vgamem_mb=64" else "bochs-display,edid=on,xres=$w,yres=$h")
            GraphicsDevice.QXL -> a += listOf("-device", if (vm.arch == GuestArch.X86_64) "qxl-vga,vgamem_mb=64,xres=$w,yres=$h" else "virtio-gpu-pci,edid=on,xres=$w,yres=$h")
        }

        // --- input: absolute tablet so the Android pointer maps 1:1 ---
        a += listOf("-device", "qemu-xhci,id=xhci", "-device", "usb-tablet,bus=xhci.0", "-device", "usb-kbd,bus=xhci.0")

        // --- audio: captured by the VNC audio extension and played through AudioTrack ---
        a += listOf("-audiodev", "none,id=snd0,out.frequency=44100,out.channels=2")
        when (vm.audio) {
            AudioDevice.NONE -> {}
            AudioDevice.HDA -> a += listOf("-device", "ich9-intel-hda,id=hda", "-device", "hda-output,audiodev=snd0,bus=hda.0")
            AudioDevice.AC97 ->
                if (vm.arch == GuestArch.X86_64) a += listOf("-device", "AC97,audiodev=snd0")
                else a += listOf("-device", "ich9-intel-hda,id=hda", "-device", "hda-output,audiodev=snd0,bus=hda.0")
            AudioDevice.VIRTIO -> a += listOf("-device", "virtio-sound-pci,audiodev=snd0,streams=1")
            AudioDevice.ES1370 -> a += listOf("-device", "ES1370,audiodev=snd0")
            AudioDevice.SB16 ->
                if (vm.arch == GuestArch.X86_64) a += listOf("-device", "sb16,audiodev=snd0")
                else a += listOf("-device", "ich9-intel-hda,id=hda", "-device", "hda-output,audiodev=snd0,bus=hda.0")
        }

        // --- storage ---
        var boot = 1
        val x86 = vm.arch == GuestArch.X86_64
        val legacyPc = x86 && vm.machine == MachineType.I440FX && !vm.firmware.isSecure
        fun ifaceOf(d: DiskInterface) = when {
            !x86 && d == DiskInterface.SATA -> DiskInterface.VIRTIO   // no IDE/AHCI on virt
            !x86 && d == DiskInterface.LSI -> DiskInterface.SCSI
            else -> d
        }
        val needScsi = (!x86 && vm.cdrom != null) || vm.disks.any { ifaceOf(it.iface) == DiskInterface.SCSI }
        if (needScsi) a += listOf("-device", "virtio-scsi-pci,id=scsi0")
        if (vm.disks.any { ifaceOf(it.iface) == DiskInterface.LSI }) a += listOf("-device", "lsi53c895a,id=lsi0")
        var sataPort = 0
        // Q35: AHCI, one device per port (ide.0..ide.5). i440FX: PIIX IDE, 2 buses x master/slave.
        fun nextIde(): String { val n = sataPort++; return if (legacyPc) "bus=ide.${n / 2},unit=${n % 2}" else "bus=ide.$n" }
        vm.cdrom?.let { iso ->
            a += listOf("-drive", "if=none,id=cd0,media=cdrom,readonly=on,format=raw,file=${escape(iso)}")
            val bi = if (vm.bootFromCd) 0 else 10
            a += listOf("-device", if (vm.arch == GuestArch.X86_64) "ide-cd,drive=cd0,${nextIde()},bootindex=$bi"
                                   else "scsi-cd,drive=cd0,bus=scsi0.0,bootindex=$bi")
        }
        vm.disks.forEachIndexed { i, d ->
            val ro = if (d.readOnly) ",readonly=on" else ""
            val discard = if (d.readOnly) "" else ",discard=unmap,detect-zeroes=unmap"
            a += listOf("-drive", "if=none,id=hd$i,format=${d.format.qemuFormat},cache=writeback,aio=threads$discard$ro,file=${escape(d.path)}")
            a += listOf("-device", when (ifaceOf(d.iface)) {
                DiskInterface.VIRTIO -> "virtio-blk-pci,drive=hd$i,bootindex=${boot++}"
                DiskInterface.SATA -> "ide-hd,drive=hd$i,${nextIde()},bootindex=${boot++}"
                DiskInterface.SCSI -> "scsi-hd,drive=hd$i,bus=scsi0.0,bootindex=${boot++}"
                DiskInterface.LSI -> "scsi-hd,drive=hd$i,bus=lsi0.0,bootindex=${boot++}"
                DiskInterface.NVME -> "nvme,drive=hd$i,serial=droidvm$i,bootindex=${boot++}"
                DiskInterface.USB -> "usb-storage,drive=hd$i,bus=xhci.0,bootindex=${boot++}"
            })
        }

        // --- network: user-mode NAT (no root) with optional host port forwards ---
        if (vm.network) {
            val bind = vm.bindAddress.ifBlank { "127.0.0.1" }
            val fw = vm.portForwards.joinToString("") { ",hostfwd=${it.proto}:$bind:${it.hostPort}-:${it.guestPort}" }
            val ip = StringBuilder()
            vm.netCidr.takeIf { it.isNotBlank() }?.let { ip.append(",net=$it") }
            vm.gatewayIp.takeIf { it.isNotBlank() }?.let { ip.append(",host=$it") }
            vm.guestIp.takeIf { it.isNotBlank() }?.let { ip.append(",dhcpstart=$it") }
            vm.dnsIp.takeIf { it.isNotBlank() }?.let { ip.append(",dns=$it") }
            // NOTE: "ipv6=on" alone makes QEMU disable IPv4, so ipv4=on is always explicit
            ip.append(if (vm.ipv6) ",ipv4=on,ipv6=on" else ",ipv4=on,ipv6=off")
            if (vm.ipv6) vm.ipv6Prefix.takeIf { it.isNotBlank() }?.let { pfx ->
                val (net, len) = pfx.split('/').let { it[0] to (it.getOrNull(1) ?: "64") }
                ip.append(",ipv6-prefix=$net,ipv6-prefixlen=$len")
            }
            vm.guestHostname.takeIf { it.isNotBlank() }?.let { ip.append(",hostname=$it") }
            vm.dnsSearch.takeIf { it.isNotBlank() }?.let { d -> d.split(',', ' ').filter { it.isNotBlank() }.forEach { ip.append(",dnssearch=$it") } }
            if (vm.restrictNet) ip.append(",restrict=on")
            vm.tftpDir?.takeIf { it.isNotBlank() }?.let {
                ip.append(",tftp=${escape(it)}")
                if (vm.tftpBootFile.isNotBlank()) ip.append(",bootfile=${escape(vm.tftpBootFile)}")
            }
            val nic = if (!x86 && vm.nic.legacyOnly) "virtio-net-pci" else vm.nic.device
            a += listOf("-netdev", "user,id=net0$ip$fw", "-device", "$nic,netdev=net0,mac=${mac(vm.id, 0)}")
        }
        // --- optional VM-to-VM Ethernet link over TCP (works between VMs on this device or across the LAN) ---
        if (vm.vmLink != tech.astrix.droidvm.data.VmLinkMode.OFF) {
            val bind = vm.bindAddress.ifBlank { "127.0.0.1" }
            val nd = if (vm.vmLink == tech.astrix.droidvm.data.VmLinkMode.LISTEN)
                "stream,id=link0,server=on,addr.type=inet,addr.host=$bind,addr.port=${vm.vmLinkPort}"
            else "stream,id=link0,server=off,addr.type=inet,addr.host=${vm.vmLinkHost},addr.port=${vm.vmLinkPort},reconnect-ms=3000"
            val nic = if (!x86 && vm.nic.legacyOnly) "virtio-net-pci" else vm.nic.device
            a += listOf("-netdev", nd, "-device", "$nic,netdev=link0,mac=${mac(vm.id, 1)}")
        }

        // --- shared folder (9p / VirtFS); mount in guest: mount -t 9p -o trans=virtio droidvm /mnt ---
        vm.sharedFolder?.takeIf { it.isNotBlank() }?.let {
            a += listOf("-virtfs", "local,path=${escape(it)},mount_tag=droidvm,security_model=none,id=share0")
        }

        // --- RNG for faster guest entropy ---
        a += listOf("-device", "virtio-rng-pci")

        // --- control + display channels (unix sockets, private to the app) ---
        a += listOf("-qmp", "unix:${paths.qmp.absolutePath},server=on,wait=off")
        val bindAddr = vm.bindAddress.ifBlank { "127.0.0.1" }
        val ws = vm.websocketPort?.let { ",websocket=$bindAddr:$it" } ?: ""
        a += listOf("-vnc", "unix:${paths.vnc.absolutePath},id=vnc0,audiodev=snd0$ws")
        vm.exposeVncPort?.let { port ->
            // Optional second VNC server for external VNC viewers (display = port-5900)
            a += listOf("-vnc", "$bindAddr:${(port - 5900).coerceAtLeast(0)},id=vnc1,audiodev=snd0")
        }
        vm.spicePort?.let { port ->
            a += listOf("-spice", "port=$port,addr=$bindAddr,disable-ticketing=on")
            // clipboard / resolution agent channel for spice-vdagent in the guest
            a += listOf("-device", "virtio-serial-pci,id=vser0", "-chardev", "spicevmc,id=vdagent,name=vdagent",
                "-device", "virtserialport,bus=vser0.0,chardev=vdagent,name=com.redhat.spice.0")
        }
        // Serial console ttyS0: always available to the built-in console (unix socket + log file);
        // optionally also on TCP (Telnet or raw). Both backends are joined by a chardev hub (QEMU 10+).
        a += listOf("-monitor", "none")
        a += listOf("-chardev", "socket,id=con0,path=${paths.console.absolutePath},server=on,wait=off,logfile=${paths.serialLog.absolutePath}")
        val tp = vm.telnetSerialPort
        if (tp != null) {
            val telnet = if (vm.serialRawTcp) "" else ",telnet=on"
            a += listOf("-chardev", "socket,id=con1,host=$bindAddr,port=$tp,server=on,wait=off$telnet",
                "-chardev", "hub,id=conhub,chardevs.0=con0,chardevs.1=con1", "-serial", "chardev:conhub")
        } else a += listOf("-serial", "chardev:con0")
        a += listOf("-pidfile", paths.pid.absolutePath)
        loadSnapshot?.let { a += listOf("-loadvm", it) }
        a += splitArgs(vm.extraArgs)
        return a
    }

    /** Stable, locally administered MAC per VM so that two VMs on one link never clash. */
    fun mac(id: String, nic: Int): String {
        val h = (id.hashCode().toLong() and 0xffffffL) xor (nic.toLong() shl 20)
        return "52:54:%02x:%02x:%02x:%02x".format((h shr 24) and 0xff or (nic.toLong() and 0xff), (h shr 16) and 0xff, (h shr 8) and 0xff, h and 0xff)
    }

    /** QEMU's option parser treats ',' as separator; a literal comma is written as ',,'. */
    fun escape(path: String) = path.replace(",", ",,")
    private fun sanitize(s: String) = s.replace(",", " ").replace("\n", " ").ifBlank { "vm" }

    fun splitArgs(s: String): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder(); var q: Char? = null
        for (c in s) {
            when {
                q != null && c == q -> q = null
                q == null && (c == '"' || c == '\'') -> q = c
                q == null && c.isWhitespace() -> { if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() } }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }
}
