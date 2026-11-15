package tech.astrix.droidvm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.astrix.droidvm.data.GuestArch
import tech.astrix.droidvm.data.ImageFormat
import tech.astrix.droidvm.data.ImageInspector
import java.io.File

class ImageInspectorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sh(vararg c: String): Int = ProcessBuilder(*c).redirectErrorStream(true).start().let { it.inputStream.readBytes(); it.waitFor() }
    private fun has(tool: String) = File("/usr/bin/$tool").exists()

    @Test fun qcow2() {
        assumeTrue(has("qemu-img"))
        val f = File(tmp.root, "x.img"); assertEquals(0, sh("qemu-img", "create", "-f", "qcow2", f.path, "1G"))
        assertEquals(ImageFormat.QCOW2, ImageInspector.inspect(f).format)
    }

    @Test fun rawMbrDisk() {
        val f = File(tmp.root, "disk.img")
        val b = ByteArray(1 shl 20); b[510] = 0x55; b[511] = 0xAA.toByte(); f.writeBytes(b)
        assertTrue(ImageInspector.inspect(f).format in setOf(ImageFormat.IMG, ImageFormat.RAW))
    }

    @Test fun compressedRejected() {
        val f = File(tmp.root, "a.img.xz"); f.writeBytes(byteArrayOf(0xFD.toByte(), '7'.code.toByte(), 'z'.code.toByte(), 'X'.code.toByte(), 'Z'.code.toByte(), 0) + ByteArray(4096))
        val r = ImageInspector.inspect(f); assertNull(r.format); assertNotNull(r.error)
    }

    @Test fun isoWithEfiLoaderDetectsArch() {
        assumeTrue(has("xorriso") || has("genisoimage") || has("mkisofs"))
        val dir = File(tmp.root, "iso/EFI/BOOT").apply { mkdirs() }
        File(dir, "BOOTAA64.EFI").writeText("x")
        val iso = File(tmp.root, "linux.iso")
        val code = if (has("xorriso")) sh("xorriso", "-as", "mkisofs", "-V", "TESTLIVE", "-o", iso.path, File(tmp.root, "iso").path)
                   else sh(if (has("genisoimage")) "genisoimage" else "mkisofs", "-V", "TESTLIVE", "-o", iso.path, File(tmp.root, "iso").path)
        assertEquals(0, code)
        val r = ImageInspector.inspect(iso)
        assertEquals(ImageFormat.ISO, r.format)
        assertEquals(GuestArch.AARCH64, r.arch)
    }
}
