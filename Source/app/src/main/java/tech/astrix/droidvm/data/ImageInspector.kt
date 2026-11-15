package tech.astrix.droidvm.data

import java.io.File
import java.io.RandomAccessFile

/**
 * Validates imported images by their real on-disk structure (magic numbers), never by file
 * extension alone, and extracts useful facts (ISO volume label, EFI boot loaders -> guest
 * architecture, partition table type).
 */
object ImageInspector {

    data class Result(
        val format: ImageFormat?,
        val arch: GuestArch? = null,
        val details: String = "",
        val error: String? = null,
    )

    private const val SECTOR = 2048

    fun inspect(file: File, nameHint: String = file.name): Result {
        if (!file.exists() || file.length() < 512) return Result(null, error = "File is empty or too small to be a disk image")
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(512).also { raf.readFully(it) }
            // QCOW2: "QFI\xfb"
            if (head[0] == 'Q'.code.toByte() && head[1] == 'F'.code.toByte() && head[2] == 'I'.code.toByte() && head[3] == 0xFB.toByte()) {
                val version = be32(head, 4)
                val virtualSize = be64(head, 24)
                return Result(ImageFormat.QCOW2, guessArchFromName(nameHint),
                    "QCOW2 v$version, virtual size ${human(virtualSize)}")
            }
            if (isCompressed(head)) return Result(null, error = "The file is compressed (xz/gzip/zip/bz2/zstd). Decompress it first and import the .img/.iso inside.")
            if (head.copyOfRange(0, 4).contentEquals(byteArrayOf(0x4B, 0x44, 0x4D, 0x56)))
                return Result(null, error = "VMDK image detected. Convert it to QCOW2 first (qemu-img convert -O qcow2).")
            // ISO 9660: "CD001" at offset 0x8001 (sector 16)
            if (file.length() > 16L * SECTOR + 6) {
                val pvd = ByteArray(SECTOR)
                raf.seek(16L * SECTOR); raf.readFully(pvd)
                if (String(pvd, 1, 5, Charsets.US_ASCII) == "CD001") {
                    val label = String(pvd, 40, 32, Charsets.US_ASCII).trim()
                    val efi = runCatching { findEfiLoaders(raf, pvd) }.getOrDefault(emptyList())
                    val arch = when {
                        efi.any { it.contains("AA64") } -> GuestArch.AARCH64
                        efi.any { it.contains("X64") } -> GuestArch.X86_64
                        else -> guessArchFromName(nameHint + " " + label)
                    }
                    val boot = if (efi.isEmpty()) "BIOS/El Torito" else "EFI: " + efi.joinToString()
                    return Result(ImageFormat.ISO, arch, "ISO 9660 \"$label\" • $boot")
                }
            }
            // Raw disk image: MBR (0x55AA) / GPT ("EFI PART" at LBA1)
            val mbr = (head[510].toInt() and 0xFF) == 0x55 && (head[511].toInt() and 0xFF) == 0xAA
            val gptBuf = ByteArray(8)
            raf.seek(512); raf.read(gptBuf)
            val gpt = String(gptBuf, Charsets.US_ASCII) == "EFI PART"
            val raw = if (nameHint.lowercase().endsWith(".img")) ImageFormat.IMG else ImageFormat.RAW
            val table = when { gpt -> "GPT partition table"; mbr -> "MBR partition table"; else -> null }
            if (table == null && file.length() % 512 != 0L)
                return Result(null, error = "Not a recognised ISO/QCOW2 image and size is not a multiple of 512 bytes - not a raw disk image")
            return Result(raw, guessArchFromName(nameHint),
                (table ?: "No partition table (raw filesystem or blank disk)") + ", ${human(file.length())}")
        }
    }

    private fun isCompressed(h: ByteArray): Boolean {
        fun m(vararg b: Int) = b.indices.all { (h[it].toInt() and 0xFF) == b[it] }
        return m(0xFD, '7'.code, 'z'.code, 'X'.code, 'Z'.code) || m(0x1F, 0x8B) || m('P'.code, 'K'.code, 3, 4) ||
            m('B'.code, 'Z'.code, 'h'.code) || m(0x28, 0xB5, 0x2F, 0xFD)
    }

    /** Walks the ISO9660 tree: / -> EFI -> BOOT and returns boot loader file names. */
    private fun findEfiLoaders(raf: RandomAccessFile, pvd: ByteArray): List<String> {
        val rootLba = le32(pvd, 156 + 2)
        val rootLen = le32(pvd, 156 + 10)
        val efi = listDir(raf, rootLba, rootLen).firstOrNull { it.name.equals("EFI", true) && it.dir } ?: return emptyList()
        val boot = listDir(raf, efi.lba, efi.len).firstOrNull { it.name.equals("BOOT", true) && it.dir } ?: return emptyList()
        return listDir(raf, boot.lba, boot.len).map { it.name.substringBefore(';').uppercase() }
            .filter { it.endsWith(".EFI") }
    }

    private data class Entry(val name: String, val lba: Long, val len: Long, val dir: Boolean)

    private fun listDir(raf: RandomAccessFile, lba: Long, len: Long): List<Entry> {
        val size = len.coerceAtMost(1L shl 20).toInt()
        val buf = ByteArray(size)
        raf.seek(lba * SECTOR); raf.readFully(buf)
        val out = ArrayList<Entry>()
        var p = 0
        while (p < size) {
            val recLen = buf[p].toInt() and 0xFF
            if (recLen == 0) { p = (p / SECTOR + 1) * SECTOR; continue }
            val nameLen = buf[p + 32].toInt() and 0xFF
            val name = String(buf, p + 33, nameLen, Charsets.ISO_8859_1)
            if (nameLen > 1 || (buf[p + 33].toInt() > 1)) {
                out += Entry(name, le32(buf, p + 2), le32(buf, p + 10), (buf[p + 25].toInt() and 2) != 0)
            }
            p += recLen
        }
        return out
    }

    fun guessArchFromName(s: String): GuestArch? {
        val l = s.lowercase()
        return when {
            listOf("aarch64", "arm64").any { it in l } -> GuestArch.AARCH64
            listOf("x86_64", "amd64", "x86-64", "x64", "i686", "i386").any { it in l } -> GuestArch.X86_64
            else -> null
        }
    }

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
    private fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
    private fun be64(b: ByteArray, o: Int): Long = (be32(b, o) shl 32) or be32(b, o + 4)

    fun human(bytes: Long): String {
        val u = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble(); var i = 0
        while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
        return if (i == 0) "$bytes B" else String.format(java.util.Locale.US, "%.1f %s", v, u[i])
    }
}
