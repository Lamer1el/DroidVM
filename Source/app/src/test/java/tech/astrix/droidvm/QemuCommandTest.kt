package tech.astrix.droidvm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.astrix.droidvm.data.Accel
import tech.astrix.droidvm.data.AudioDevice
import tech.astrix.droidvm.data.DiskInterface
import tech.astrix.droidvm.data.Firmware
import tech.astrix.droidvm.data.GraphicsDevice
import tech.astrix.droidvm.data.GuestArch
import tech.astrix.droidvm.data.ImageFormat
import tech.astrix.droidvm.data.PortForward
import tech.astrix.droidvm.data.VmConfig
import tech.astrix.droidvm.data.VmDisk
import tech.astrix.droidvm.engine.QemuCommand
import tech.astrix.droidvm.engine.VmPaths
import java.io.File

class QemuCommandTest {
    private val paths = VmPaths(File("/data/vm/abc"))

    private fun build(vm: VmConfig, snap: String? = null) = QemuCommand.build(vm, "/bin/qemu", "/share", paths, snap)
    private fun List<String>.valuesOf(opt: String) = indices.filter { this[it] == opt }.map { this[it + 1] }

    @Test fun x86BiosWithIsoAndDisk() {
        val vm = VmConfig(id = "abc", name = "Debian, test", ramMb = 3072, cpus = 4,
            cdrom = "/img/debian,1.iso", disks = listOf(VmDisk("d", "/d/disk0.qcow2", ImageFormat.QCOW2)),
            portForwards = listOf(PortForward("tcp", 2222, 22), PortForward("tcp", 8080, 80)))
        val a = build(vm)
        assertEquals("/bin/qemu", a[0])
        assertTrue(a.valuesOf("-machine").single().startsWith("q35"))
        assertEquals("tcg,thread=multi,tb-size=256", a.valuesOf("-accel").single())
        assertEquals("3072M", a.valuesOf("-m").single())
        assertEquals("4", a.valuesOf("-smp").single())
        assertTrue(a.valuesOf("-name").single().startsWith("Debian  test,"))
        val drives = a.valuesOf("-drive")
        assertTrue(drives.any { it.contains("media=cdrom") && it.endsWith("file=/img/debian,,1.iso") })
        assertTrue(drives.any { it.contains("format=qcow2") && it.endsWith("file=/d/disk0.qcow2") })
        assertFalse(drives.any { it.startsWith("if=pflash") })
        val devs = a.valuesOf("-device")
        assertTrue(devs.contains("ide-cd,drive=cd0,bus=ide.0,bootindex=0"))
        assertTrue(devs.contains("virtio-blk-pci,drive=hd0,bootindex=1"))
        assertTrue(devs.any { it.startsWith("VGA,") && it.contains("xres=1280,yres=720") })
        assertTrue(a.valuesOf("-netdev").single().contains("hostfwd=tcp:127.0.0.1:2222-:22,hostfwd=tcp:127.0.0.1:8080-:80"))
        assertEquals("unix:/data/vm/abc/vnc.sock,id=vnc0,audiodev=snd0", a.valuesOf("-vnc").single())
        assertEquals("unix:/data/vm/abc/qmp.sock,server=on,wait=off", a.valuesOf("-qmp").single())
        assertTrue(a.valuesOf("-audiodev").single().startsWith("none,id=snd0"))
    }

    @Test fun aarch64UsesVirtUefiAndScsiCd() {
        val vm = VmConfig(id = "abc", name = "arm", arch = GuestArch.AARCH64, graphics = GraphicsDevice.VIRTIO_GPU,
            audio = AudioDevice.VIRTIO, cdrom = "/i.iso",
            disks = listOf(VmDisk("d", "/d.img", ImageFormat.RAW, DiskInterface.SATA, readOnly = true)))
        val a = build(vm, QemuCommand.AUTOSAVE_TAG)
        assertTrue(a.valuesOf("-machine").single().startsWith("virt,gic-version=3"))
        assertEquals("max,pauth-impdef=on", a.valuesOf("-cpu").single())
        val drives = a.valuesOf("-drive")
        assertTrue(drives.any { it == "if=pflash,format=raw,unit=0,readonly=on,file=/share/edk2-aarch64-code.fd" })
        assertTrue(drives.any { it == "if=pflash,format=raw,unit=1,file=/data/vm/abc/efivars.fd" })
        assertTrue(drives.any { it.contains("format=raw") && it.contains("readonly=on") && !it.contains("discard") })
        val devs = a.valuesOf("-device")
        assertTrue(devs.contains("virtio-scsi-pci,id=scsi0"))
        assertTrue(devs.any { it.startsWith("scsi-cd,drive=cd0") })
        assertTrue(devs.contains("virtio-blk-pci,drive=hd0,bootindex=1")) // SATA is mapped to virtio on virt
        assertTrue(devs.any { it.startsWith("virtio-gpu-pci,edid=on") })
        assertTrue(devs.contains("virtio-sound-pci,audiodev=snd0,streams=1"))
        assertEquals(QemuCommand.AUTOSAVE_TAG, a.valuesOf("-loadvm").single())
    }

    @Test fun kvmSharedFolderExtraVncAndArgs() {
        val vm = VmConfig(id = "abc", name = "k", accel = Accel.KVM, firmware = Firmware.UEFI, network = false,
            sharedFolder = "/sdcard/DroidVM/Shared", exposeVncPort = 5901, extraArgs = "-device \"usb-mouse,bus=xhci.0\" -no-reboot")
        val a = build(vm)
        assertEquals("kvm", a.valuesOf("-accel").single())
        assertEquals("host", a.valuesOf("-cpu").single())
        assertTrue(a.valuesOf("-drive").any { it.endsWith("edk2-x86_64-code.fd") })
        assertTrue(a.valuesOf("-netdev").isEmpty())
        assertEquals("local,path=/sdcard/DroidVM/Shared,mount_tag=droidvm,security_model=none,id=share0", a.valuesOf("-virtfs").single())
        assertEquals(listOf("unix:/data/vm/abc/vnc.sock,id=vnc0,audiodev=snd0", "127.0.0.1:1,id=vnc1,audiodev=snd0"), a.valuesOf("-vnc"))
        assertEquals(listOf("-device", "usb-mouse,bus=xhci.0", "-no-reboot"), a.takeLast(3))
    }

    /** v1.2: built-in serial console socket, Telnet/raw hub, WebSocket VNC, VM link, DHCP extras, unique MACs. */
    @Test fun v12ProtocolsAndConsole() {
        val plain = build(VmConfig(id = "abc", name = "p"))
        assertTrue(plain.valuesOf("-chardev").any { it.startsWith("socket,id=con0,path=/data/vm/abc/console.sock,server=on,wait=off,logfile=/data/vm/abc/serial.log") })
        assertEquals("chardev:con0", plain.valuesOf("-serial").single())
        assertTrue(plain.valuesOf("-device").any { it.contains("netdev=net0,mac=52:54:") })

        val vm = VmConfig(id = "abc", name = "p", telnetSerialPort = 2323, serialRawTcp = true, websocketPort = 5700,
            vmLink = tech.astrix.droidvm.data.VmLinkMode.CONNECT, vmLinkHost = "192.168.1.5", vmLinkPort = 24800,
            guestHostname = "debian", dnsSearch = "lan home.arpa", ipv6Prefix = "fd00:1::/64")
        val a = build(vm)
        val ch = a.valuesOf("-chardev")
        assertTrue(ch.any { it == "socket,id=con1,host=127.0.0.1,port=2323,server=on,wait=off" })
        assertTrue(ch.contains("hub,id=conhub,chardevs.0=con0,chardevs.1=con1"))
        assertEquals("chardev:conhub", a.valuesOf("-serial").single())
        assertTrue(a.valuesOf("-vnc").single().endsWith(",websocket=127.0.0.1:5700"))
        val nd = a.valuesOf("-netdev")
        assertTrue(nd[0].contains(",hostname=debian") && nd[0].contains(",dnssearch=lan,dnssearch=home.arpa"))
        assertTrue(nd[0].contains("ipv6-prefix=fd00:1::,ipv6-prefixlen=64"))
        assertEquals("stream,id=link0,server=off,addr.type=inet,addr.host=192.168.1.5,addr.port=24800,reconnect-ms=3000", nd[1])
        val macs = a.valuesOf("-device").mapNotNull { Regex("mac=([0-9a-f:]+)").find(it)?.groupValues?.get(1) }
        assertEquals(2, macs.toSet().size)

        val telnet = build(VmConfig(id = "abc", name = "p", telnetSerialPort = 2323))
        assertTrue(telnet.valuesOf("-chardev").any { it.startsWith("socket,id=con1,") && it.endsWith(",telnet=on") })
        assertTrue(tech.astrix.droidvm.engine.QemuCommand.mac("a", 0) != tech.astrix.droidvm.engine.QemuCommand.mac("b", 0))
    }

    /** New firmware choices and direct Linux boot. */
    @Test fun firmwareVariantsAndDirectKernelBoot() {
        val ia32 = build(VmConfig(id = "abc", name = "f", firmware = Firmware.UEFI_IA32))
        assertTrue(ia32.valuesOf("-drive").contains("if=pflash,format=raw,unit=0,readonly=on,file=/share/edk2-i386-code.fd"))
        val ia32s = build(VmConfig(id = "abc", name = "f", firmware = Firmware.UEFI_IA32_SECURE, machine = tech.astrix.droidvm.data.MachineType.I440FX))
        assertTrue(ia32s.valuesOf("-drive").any { it.endsWith("file=/share/edk2-i386-secure-code.fd") })
        assertTrue(ia32s.valuesOf("-machine").single().let { it.startsWith("q35") && it.contains("smm=on") })
        assertEquals("/share/bios.bin", build(VmConfig(id = "abc", name = "f", firmware = Firmware.BIOS_128K)).valuesOf("-bios").single())
        val q = build(VmConfig(id = "abc", name = "f", firmware = Firmware.QBOOT, kernelPath = "/k/vmlinuz", initrdPath = "/k/initrd.img",
            kernelCmdline = "console=ttyS0 quiet"))
        assertEquals("/share/qboot.rom", q.valuesOf("-bios").single())
        assertEquals("/k/vmlinuz", q.valuesOf("-kernel").single())
        assertEquals("/k/initrd.img", q.valuesOf("-initrd").single())
        assertEquals("console=ttyS0 quiet", q.valuesOf("-append").single())
        assertFalse(q.valuesOf("-drive").any { it.startsWith("if=pflash") })
        val plain = build(VmConfig(id = "abc", name = "f"))
        assertTrue(plain.valuesOf("-bios").isEmpty() && plain.valuesOf("-kernel").isEmpty())
        // aarch64 always uses its own UEFI, whatever is stored
        val arm = build(VmConfig(id = "abc", name = "a", arch = GuestArch.AARCH64, firmware = Firmware.QBOOT, graphics = GraphicsDevice.VIRTIO_GPU))
        assertTrue(arm.valuesOf("-bios").isEmpty())
        assertTrue(arm.valuesOf("-drive").any { it.endsWith("edk2-aarch64-code.fd") })
    }
}
