package tech.astrix.droidvm.data

import kotlinx.serialization.Serializable
import tech.astrix.droidvm.i18n.tr

@Serializable
enum class GuestArch(val label: String, val qemuBinary: String) {
    X86_64("x86_64 (PC)", "libqemu_system_x86_64.so"),
    AARCH64("ARM64 (AArch64)", "libqemu_system_aarch64.so");
}

@Serializable
enum class ImageFormat(val label: String) {
    ISO("ISO"), QCOW2("QCOW2"), RAW("RAW"), IMG("IMG");

    val isOptical get() = this == ISO
    /** qemu "format=" value */
    val qemuFormat get() = when (this) { QCOW2 -> "qcow2"; else -> "raw" }
}

@Serializable
enum class DiskInterface(private val en: String) {
    VIRTIO("VirtIO"), SATA("SATA / IDE"), SCSI("VirtIO SCSI"), NVME("NVMe"),
    LSI("LSI 53C895A SCSI (legacy)"), USB("USB storage");
    val label: String get() = tr(en)
}

@Serializable
enum class MachineType(private val en: String, val qemu: String) {
    Q35("Q35 (modern, PCIe)", "q35"), I440FX("i440FX (legacy PC, PCI)", "pc");
    val label: String get() = tr(en)
}

@Serializable
enum class Firmware(private val en: String) {
    BIOS("BIOS (SeaBIOS)"),
    UEFI("UEFI (EDK2)"),
    UEFI_SECURE("UEFI + Secure Boot (EDK2, SMM)"),
    /** SeaBIOS 128 KiB build (bios.bin) - smaller ROM for very old guests on i440FX */
    BIOS_128K("SeaBIOS 128K (old PCs)"),
    /** 32-bit UEFI (OVMF IA32) for guests that only ship a 32-bit EFI loader (bootia32.efi) */
    UEFI_IA32("UEFI 32-bit (EDK2 IA32)"),
    UEFI_IA32_SECURE("UEFI 32-bit + Secure Boot (EDK2 IA32, SMM)"),
    /** qboot: minimal firmware that only boots a Linux kernel given with "Direct Linux boot" */
    QBOOT("qboot (fast, direct Linux boot only)");
    val label: String get() = tr(en)
    val isUefi get() = this == UEFI || this == UEFI_SECURE || this == UEFI_IA32 || this == UEFI_IA32_SECURE
    val isSecure get() = this == UEFI_SECURE || this == UEFI_IA32_SECURE
    /** firmware file for -bios (null = QEMU default SeaBIOS or pflash UEFI) */
    val biosFile: String? get() = when (this) { BIOS_128K -> "bios.bin"; QBOOT -> "qboot.rom"; else -> null }
    val uefiCode: String? get() = when (this) {
        UEFI -> "edk2-x86_64-code.fd"; UEFI_SECURE -> "edk2-x86_64-secure-code.fd"
        UEFI_IA32 -> "edk2-i386-code.fd"; UEFI_IA32_SECURE -> "edk2-i386-secure-code.fd"; else -> null
    }
}

@Serializable
enum class GraphicsDevice(private val en: String) {
    VGA("Standard VGA"), VIRTIO_GPU("VirtIO GPU"), RAMFB("ramfb"), CIRRUS("Cirrus (legacy)"),
    VMWARE("VMware SVGA II"), QXL("QXL (for SPICE)");
    val label: String get() = tr(en)
}

@Serializable
enum class AudioDevice(private val en: String) {
    NONE("Disabled"), HDA("Intel HDA"), AC97("AC97"), VIRTIO("VirtIO Sound"),
    ES1370("Ensoniq AudioPCI ES1370 (legacy)"), SB16("Sound Blaster 16 (legacy ISA)");
    val label: String get() = tr(en)
}

@Serializable
enum class NicModel(private val en: String, val device: String, val legacyOnly: Boolean = false) {
    VIRTIO("VirtIO-net", "virtio-net-pci"), E1000("Intel e1000", "e1000"), E1000E("Intel e1000e (82574L)", "e1000e"),
    RTL8139("Realtek RTL8139", "rtl8139"), VMXNET3("VMware vmxnet3", "vmxnet3"),
    PCNET("AMD PCnet (legacy)", "pcnet", true), NE2K("NE2000 PCI (legacy)", "ne2k_pci", true),
    I82559("Intel 82559ER (legacy)", "i82559er", true);
    val label: String get() = tr(en)
}

@Serializable
enum class Accel(private val en: String) {
    TCG("TCG (software emulation)"), KVM("KVM (hardware)");
    val label: String get() = tr(en)
}

@Serializable
enum class VmLinkMode(private val en: String) {
    OFF("Off"), LISTEN("Wait for connection (server)"), CONNECT("Connect to another VM (client)");
    val label: String get() = tr(en)
}

@Serializable
data class PortForward(val proto: String = "tcp", val hostPort: Int, val guestPort: Int)

@Serializable
data class VmDisk(
    val id: String,
    /** absolute path of the image file */
    val path: String,
    val format: ImageFormat,
    val iface: DiskInterface = DiskInterface.VIRTIO,
    val readOnly: Boolean = false,
    val label: String = "",
)

@Serializable
data class VmConfig(
    val id: String,
    val name: String,
    val arch: GuestArch = GuestArch.X86_64,
    val ramMb: Int = 2048,
    val cpus: Int = 2,
    val firmware: Firmware = Firmware.BIOS,
    val graphics: GraphicsDevice = GraphicsDevice.VGA,
    val width: Int = 1280,
    val height: Int = 720,
    val audio: AudioDevice = AudioDevice.HDA,
    val network: Boolean = true,
    val nic: NicModel = NicModel.VIRTIO,
    val portForwards: List<PortForward> = listOf(PortForward("tcp", 2222, 22)),
    val sharedFolder: String? = null,
    val cdrom: String? = null,
    val disks: List<VmDisk> = emptyList(),
    val accel: Accel = Accel.TCG,
    val tbSizeMb: Int = 256,
    val bootFromCd: Boolean = true,
    val localTimeRtc: Boolean = false,
    val exposeVncPort: Int? = null,
    // --- v1.1: legacy hardware, IP settings and extra protocols ---
    val machine: MachineType = MachineType.Q35,
    /** Guest network in CIDR notation (QEMU user-mode "net="). */
    val netCidr: String = "10.0.2.0/24",
    /** First address handed out by the built-in DHCP server = guest IP. */
    val guestIp: String = "10.0.2.15",
    /** Address of the virtual gateway/host inside the guest network. */
    val gatewayIp: String = "10.0.2.2",
    val dnsIp: String = "10.0.2.3",
    /** Android-side address the forwards/servers listen on: 127.0.0.1 (this device only) or 0.0.0.0 (LAN). */
    val bindAddress: String = "127.0.0.1",
    val ipv6: Boolean = true,
    /** Isolate guest from the internet (forwards still work). */
    val restrictNet: Boolean = false,
    /** Serial console over Telnet (RFC 854) on bindAddress:port. */
    val telnetSerialPort: Int? = null,
    /** SPICE server on bindAddress:port (for aSPICE / remote-viewer). */
    val spicePort: Int? = null,
    /** Built-in TFTP server root for PXE network boot (legacy BOOTP/TFTP). */
    val tftpDir: String? = null,
    val tftpBootFile: String = "",
    // --- v1.2: more protocols ---
    /** Serial console protocol on [telnetSerialPort]: Telnet (RFC 854) or raw TCP (nc / socat). */
    val serialRawTcp: Boolean = false,
    /** WebSocket VNC (RFB over WebSocket) for noVNC / browsers. */
    val websocketPort: Int? = null,
    /** Second NIC: Ethernet-over-TCP link to another QEMU/DroidVM VM (QEMU "stream" netdev). */
    val vmLink: VmLinkMode = VmLinkMode.OFF,
    val vmLinkHost: String = "127.0.0.1",
    val vmLinkPort: Int = 24800,
    /** DHCP options of the built-in server. */
    val guestHostname: String = "",
    val dnsSearch: String = "",
    /** IPv6 prefix of the guest network, e.g. fd00:dead:beef::/64 (empty = QEMU default fec0::/64). */
    val ipv6Prefix: String = "",
    /** Open the text console instead of the graphical display when the VM starts (headless / servers). */
    val openConsoleOnStart: Boolean = false,
    /** Direct Linux boot: QEMU loads this kernel (bzImage / Image) itself, no boot loader needed. */
    val kernelPath: String = "",
    val initrdPath: String = "",
    val kernelCmdline: String = "",
    val extraArgs: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val notes: String = "",
)

@Serializable
data class ImageEntry(
    val id: String,
    val name: String,
    val path: String,
    val format: ImageFormat,
    val sizeBytes: Long,
    val detectedArch: GuestArch? = null,
    val details: String = "",
    val importedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class AppSettings(
    val theme: Int = 0, // 0 system, 1 light, 2 dark
    val dynamicColor: Boolean = false,
    val keepScreenOn: Boolean = true,
    val defaultTouchMode: Int = 0, // 0 touchpad, 1 direct
    val audioVolume: Float = 1f,
    /** Termux-style: hold a partial wake lock only when the user asks for it (notification / settings). */
    val wakeLock: Boolean = false,
    /** Key combination sent to the guest by the layout button on the display toolbar (see LayoutSwitch). */
    val layoutSwitch: LayoutSwitch = LayoutSwitch.ALT_SHIFT,
)

/** How the guest OS switches the keyboard layout (EN ⇄ RU / KK ...). */
@Serializable
enum class LayoutSwitch(val en: String) {
    ALT_SHIFT("Alt+Shift"), CTRL_SHIFT("Ctrl+Shift"), SUPER_SPACE("Super+Space"), CTRL_SPACE("Ctrl+Space"),
    ALT_SPACE("Alt+Space"), CAPS_LOCK("Caps Lock"), SHIFT_CAPS("Shift+Caps Lock");
    val label: String get() = en
}

data class Snapshot(val id: String, val tag: String, val vmSize: String, val date: String, val vmClock: String)
