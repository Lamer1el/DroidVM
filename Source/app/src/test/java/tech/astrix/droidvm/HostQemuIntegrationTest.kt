package tech.astrix.droidvm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.astrix.droidvm.data.AudioDevice
import tech.astrix.droidvm.data.DiskInterface
import tech.astrix.droidvm.data.Firmware
import tech.astrix.droidvm.data.GraphicsDevice
import tech.astrix.droidvm.data.MachineType
import tech.astrix.droidvm.data.NicModel
import tech.astrix.droidvm.data.ImageFormat
import tech.astrix.droidvm.data.VmConfig
import tech.astrix.droidvm.data.VmDisk
import tech.astrix.droidvm.engine.QemuCommand
import tech.astrix.droidvm.engine.QmpClient
import tech.astrix.droidvm.engine.StreamConnection
import tech.astrix.droidvm.engine.VmPaths
import tech.astrix.droidvm.vnc.RfbClient
import tech.astrix.droidvm.vnc.RfbListener
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.SocketChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs the exact command line DroidVM generates against the host's QEMU and talks to it with the app's QMP and RFB clients. */
class HostQemuIntegrationTest {
    @get:Rule val tmp = TemporaryFolder()

    class UnixConn(path: String) : StreamConnection {
        // JDK 16+ unix-domain sockets, via reflection because unit tests compile against android.jar
        private val ch: SocketChannel = run {
            val family = Class.forName("java.net.StandardProtocolFamily").getField("UNIX").get(null)
            val pf = Class.forName("java.net.ProtocolFamily")
            val c = SocketChannel::class.java.getMethod("open", pf).invoke(null, family) as SocketChannel
            val addr = Class.forName("java.net.UnixDomainSocketAddress").getMethod("of", String::class.java).invoke(null, path) as java.net.SocketAddress
            c.connect(addr); c
        }
        // Channels.newInputStream/newOutputStream serialise reads and writes on one lock; use the channel directly.
        override val input: InputStream = object : InputStream() {
            override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF }
            override fun read(b: ByteArray, off: Int, len: Int): Int = ch.read(java.nio.ByteBuffer.wrap(b, off, len))
        }
        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) { val bb = java.nio.ByteBuffer.wrap(b, off, len); while (bb.hasRemaining()) ch.write(bb) }
        }
        override fun close() = ch.close()
    }

    private val qemu = File("/usr/bin/qemu-system-x86_64")
    private fun waitFor(f: File) { val end = System.currentTimeMillis() + 20000; while (!f.exists() && System.currentTimeMillis() < end) Thread.sleep(100) }

    private fun launch(vm: VmConfig, dir: File, snap: String? = null, share: String = "/usr/share/qemu"): Process {
        val cmd = QemuCommand.build(vm, qemu.path, share, VmPaths(dir), snap)
        return ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(File(dir, "out.log")).start()
    }

    @Test fun bootDisplayQmpSnapshotAndSavedState() {
        assumeTrue(qemu.exists())
        val dir = tmp.newFolder("vm")
        val disk = File(dir, "disk.qcow2")
        assertEquals(0, ProcessBuilder("qemu-img", "create", "-f", "qcow2", disk.path, "1G").start().waitFor())
        val share = tmp.newFolder("share")
        val vm = VmConfig(id = "t1", name = "host test", ramMb = 256, cpus = 2, audio = AudioDevice.HDA,
            disks = listOf(VmDisk("d", disk.path, ImageFormat.QCOW2)), sharedFolder = share.path, width = 1024, height = 768)
        var p = launch(vm, dir)
        try {
            val paths = VmPaths(dir)
            waitFor(paths.qmp); waitFor(paths.vnc)
            Thread.sleep(500)
            assertTrue("QEMU did not start: " + File(dir, "out.log").readText(), paths.qmp.exists() && p.isAlive)
            val events = ArrayList<String>()
            val q = QmpClient(UnixConn(paths.qmp.path)) { e, _ -> synchronized(events) { events += e } }
            q.handshake()
            assertTrue(q.version.isNotEmpty())
            assertTrue(q.status().contains("running"))

            // RFB: connect, enable audio, receive real frames
            val frame = CountDownLatch(3)
            var w = 0; var h = 0
            val rfb = RfbClient(UnixConn(paths.vnc.path), object : RfbListener {
                override fun onConnected(name: String, width: Int, height: Int) { w = width; h = height }
                override fun onResize(width: Int, height: Int) { w = width; h = height }
                override fun onUpdateComplete() { frame.countDown() }
            })
            rfb.start()
            assertTrue("no frames", frame.await(20, TimeUnit.SECONDS))
            assertTrue(w > 0 && h > 0)
            assertTrue("QEMU extended key event", rfb.extKeySupported)
            assertTrue("QEMU audio extension", rfb.audioSupported)
            rfb.keyScancode(0xff0d, 0x1c, true); rfb.keyScancode(0xff0d, 0x1c, false)
            rfb.pointer(10, 10, 1); rfb.pointer(10, 10, 0)

            // pause / resume
            q.execute("stop"); Thread.sleep(300); assertTrue(q.status().contains("paused"))
            q.execute("cont"); Thread.sleep(300); assertTrue(q.status().contains("running"))

            // live snapshot (shared folder not mounted in a guest -> allowed)
            val out = q.hmp("savevm snap1")
            assertEquals("", out.trim())
            assertTrue(q.hmp("info snapshots").contains("snap1"))

            // save state & quit, like VmManager.saveStateAndQuit
            q.execute("stop")
            assertEquals("", q.hmp("savevm ${QemuCommand.AUTOSAVE_TAG}").trim())
            runCatching { q.execute("quit", timeoutMs = 5000) }
            rfb.close()
            assertTrue(p.waitFor(20, TimeUnit.SECONDS))
            val info = ProcessBuilder("qemu-img", "snapshot", "-l", disk.path).start().inputStream.bufferedReader().readText()
            assertTrue(info, info.contains(QemuCommand.AUTOSAVE_TAG) && info.contains("snap1"))

            // resume from saved state
            paths.qmp.delete(); paths.vnc.delete()
            p = launch(vm, dir, QemuCommand.AUTOSAVE_TAG)
            waitFor(paths.qmp)
            val q2 = QmpClient(UnixConn(paths.qmp.path)); q2.handshake()
            assertTrue(q2.status().contains("running"))
            q2.execute("system_powerdown")
            runCatching { q2.execute("quit", timeoutMs = 5000) }
            p.waitFor(20, TimeUnit.SECONDS)
        } finally {
            p.destroyForcibly()
            println(File(dir, "out.log").takeIf { it.exists() }?.readText())
        }
    }

    /** v1.1: legacy i440FX hardware + custom IP network + SPICE + Telnet + TFTP must be accepted by real QEMU. */
    @Test fun legacyHardwareAndProtocolsStart() {
        assumeTrue(qemu.exists())
        val variants = listOf(
            VmConfig(id = "l1", name = "legacy", ramMb = 128, machine = MachineType.I440FX, nic = NicModel.PCNET,
                audio = AudioDevice.SB16, graphics = GraphicsDevice.CIRRUS, netCidr = "192.168.50.0/24", guestIp = "192.168.50.20",
                gatewayIp = "192.168.50.1", dnsIp = "192.168.50.3", ipv6 = false, restrictNet = true,
                telnetSerialPort = 24123, spicePort = 25931, tftpDir = tmp.newFolder("tftp").path, tftpBootFile = "pxelinux.0",
                disks = listOf(VmDisk("d", File(tmp.root, "l.qcow2").also { qimg(it) }.path, ImageFormat.QCOW2, DiskInterface.SATA),
                    VmDisk("e", File(tmp.root, "l2.qcow2").also { qimg(it) }.path, ImageFormat.QCOW2, DiskInterface.LSI))),
            VmConfig(id = "l2", name = "nics", ramMb = 128, machine = MachineType.I440FX, nic = NicModel.NE2K, audio = AudioDevice.ES1370,
                graphics = GraphicsDevice.VMWARE, disks = listOf(VmDisk("d", File(tmp.root, "n.qcow2").also { qimg(it) }.path, ImageFormat.QCOW2, DiskInterface.NVME))),
            VmConfig(id = "l3", name = "i82559", ramMb = 128, nic = NicModel.I82559, graphics = GraphicsDevice.QXL, spicePort = 25932,
                disks = listOf(VmDisk("d", File(tmp.root, "s.qcow2").also { qimg(it) }.path, ImageFormat.QCOW2, DiskInterface.SCSI))),
            VmConfig(id = "l4", name = "secure", ramMb = 256, firmware = Firmware.UEFI_SECURE, nic = NicModel.VMXNET3,
                disks = listOf(VmDisk("d", File(tmp.root, "u.qcow2").also { qimg(it) }.path, ImageFormat.QCOW2))),
        )
        for (vm in variants) {
            val dir = tmp.newFolder("v" + vm.id)
            if (vm.firmware.isUefi) File("src/main/assets/qemu/share/edk2-i386-vars.fd").copyTo(VmPaths(dir).efiVars)
            // the Secure Boot firmware is shipped by the app (src/main/assets/qemu/share), not by every host QEMU
            val p = launch(vm, dir, share = if (vm.firmware == Firmware.UEFI_SECURE) File("src/main/assets/qemu/share").absolutePath else "/usr/share/qemu")
            try {
                waitFor(VmPaths(dir).qmp)
                Thread.sleep(1500)
                assertTrue("${vm.name}: " + File(dir, "out.log").readText(), p.isAlive)
                vm.telnetSerialPort?.let { port -> java.net.Socket("127.0.0.1", port).use { assertTrue(it.isConnected) } }
                vm.spicePort?.let { port -> java.net.Socket("127.0.0.1", port).use { assertTrue(it.isConnected) } }
            } finally { p.destroyForcibly(); p.waitFor() }
        }
    }

    /** v1.2: chardev hub (console socket + raw TCP), VNC WebSocket and a VM-to-VM stream link between two real QEMUs. */
    @Test fun v12HubWebsocketAndVmLink() {
        assumeTrue(qemu.exists())
        val server = VmConfig(id = "s1", name = "server", ramMb = 128, telnetSerialPort = 24223, serialRawTcp = true, websocketPort = 25701,
            vmLink = tech.astrix.droidvm.data.VmLinkMode.LISTEN, vmLinkPort = 24811)
        val client = VmConfig(id = "c1", name = "client", ramMb = 128, portForwards = emptyList(),
            vmLink = tech.astrix.droidvm.data.VmLinkMode.CONNECT, vmLinkHost = "127.0.0.1", vmLinkPort = 24811)
        val d1 = tmp.newFolder("s1"); val d2 = tmp.newFolder("c1")
        val p1 = launch(server, d1)
        var p2: Process? = null
        try {
            waitFor(VmPaths(d1).qmp)
            waitFor(VmPaths(d1).console)
            java.net.Socket("127.0.0.1", 24223).use { assertTrue(it.isConnected) }
            java.net.Socket("127.0.0.1", 25701).use { s ->
                s.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: binary\r\n\r\n".toByteArray())
                s.soTimeout = 5000
                val resp = ByteArray(256).let { b -> String(b, 0, s.getInputStream().read(b)) }
                assertTrue(resp, resp.startsWith("HTTP/1.1 101"))
            }
            UnixConn(VmPaths(d1).console.path).use { assertTrue(true) }
            p2 = launch(client, d2)
            waitFor(VmPaths(d2).qmp)
            Thread.sleep(2500)
            assertTrue(File(d2, "out.log").readText(), p2.isAlive)
            val q = QmpClient(UnixConn(VmPaths(d1).qmp.path)); q.handshake()
            val info = q.hmp("info network")
            assertTrue(info, info.contains("link0"))
            assertTrue(p1.isAlive)
        } finally {
            p2?.destroyForcibly(); p2?.waitFor()
            p1.destroyForcibly(); p1.waitFor()
            println(File(d1, "out.log").takeIf { it.exists() }?.readText())
        }
    }

    /** New firmware (32-bit UEFI, 32-bit UEFI Secure Boot, SeaBIOS 128K, qboot + direct kernel boot) shipped in the APK. */
    @Test fun newFirmwareStarts() {
        assumeTrue(qemu.exists())
        val share = File("src/main/assets/qemu/share").absolutePath
        val variants = listOf(
            VmConfig(id = "f1", name = "ia32", ramMb = 256, firmware = Firmware.UEFI_IA32, network = false),
            VmConfig(id = "f2", name = "ia32s", ramMb = 256, firmware = Firmware.UEFI_IA32_SECURE, network = false),
            VmConfig(id = "f3", name = "bios128", ramMb = 128, firmware = Firmware.BIOS_128K, machine = MachineType.I440FX, network = false),
        )
        for (vm in variants) {
            val dir = tmp.newFolder("fw" + vm.id)
            if (vm.firmware.isUefi) File(share, "edk2-i386-vars.fd").copyTo(VmPaths(dir).efiVars)
            File(dir, "d.qcow2").also { qimg(it) }
            val p = launch(vm.copy(disks = listOf(VmDisk("d", File(dir, "d.qcow2").path, ImageFormat.QCOW2))), dir, share = share)
            try {
                waitFor(VmPaths(dir).qmp); Thread.sleep(4000)
                assertTrue("${vm.name}: " + File(dir, "out.log").readText(), p.isAlive)
                val q = QmpClient(UnixConn(VmPaths(dir).qmp.path)); q.handshake()
                assertTrue(q.status().contains("running"))
            } finally { p.destroyForcibly(); p.waitFor() }
        }
        // qboot + direct Linux boot: needs a real x86_64 kernel (set DROIDVM_TEST_KERNEL)
        val kernel = System.getenv("DROIDVM_TEST_KERNEL")?.let(::File)?.takeIf { it.isFile } ?: return
        val dir = tmp.newFolder("qboot")
        val vm = VmConfig(id = "q1", name = "qboot", ramMb = 512, firmware = Firmware.QBOOT, network = false,
            kernelPath = kernel.path, kernelCmdline = "console=ttyS0 panic=-1")
        val p = launch(vm, dir, share = share)
        try {
            val log = File(dir, "serial.log")
            val end = System.currentTimeMillis() + 60000
            while (System.currentTimeMillis() < end && !(log.exists() && log.readText().contains("Linux version"))) Thread.sleep(250)
            assertTrue(File(dir, "out.log").readText(), log.exists() && log.readText().contains("Linux version"))
        } finally { p.destroyForcibly(); p.waitFor() }
    }

    private fun qimg(f: File) = assertEquals(0, ProcessBuilder("qemu-img", "create", "-f", "qcow2", f.path, "64M").start().waitFor())
}
