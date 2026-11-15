package tech.astrix.droidvm.vnc

import tech.astrix.droidvm.engine.StreamConnection
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

interface RfbListener {
    fun onConnected(name: String, width: Int, height: Int) {}
    fun onResize(width: Int, height: Int) {}
    /** A rectangle of [fb] (row stride = width) has changed. Called on the RFB thread. */
    fun onRect(fb: IntArray, fbWidth: Int, x: Int, y: Int, w: Int, h: Int) {}
    fun onUpdateComplete() {}
    fun onCursor(pixels: IntArray?, w: Int, h: Int, hotX: Int, hotY: Int) {}
    fun onAudioFormat(sampleRate: Int, channels: Int) {}
    fun onAudioStart() {}
    fun onAudioData(data: ByteArray, len: Int) {}
    fun onAudioStop() {}
    fun onBell() {}
    fun onClipboard(text: String) {}
    fun onDisconnected(error: Throwable?) {}
}

/**
 * RFB 3.8 client for QEMU's built-in VNC server.
 *
 * Supported: None security, 32bpp true colour, Raw + CopyRect, DesktopSize, RichCursor,
 * QEMU Extended Key Event (raw PC scancodes) and the QEMU audio extension (PCM S16LE
 * streamed to the client - this is how guest sound reaches Android without PulseAudio).
 */
class RfbClient(
    private val conn: StreamConnection,
    private val listener: RfbListener,
    private val audio: Boolean = true,
    private val sampleRate: Int = 44100,
    private val channels: Int = 2,
) {
    companion object {
        const val ENC_RAW = 0
        const val ENC_COPYRECT = 1
        const val ENC_DESKTOP_SIZE = -223
        const val ENC_RICH_CURSOR = -239
        const val ENC_QEMU_EXT_KEY = -258
        const val ENC_QEMU_AUDIO = -259
        const val AUD_FMT_S16 = 3
    }

    private val inp = DataInputStream(conn.input.buffered(1 shl 16))
    private val out = DataOutputStream(conn.output.buffered(4096))
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "rfb-writer").apply { isDaemon = true } }
    @Volatile var width = 0; private set
    @Volatile var height = 0; private set
    @Volatile var serverName = ""; private set
    @Volatile var extKeySupported = false; private set
    @Volatile var audioSupported = false; private set
    @Volatile private var running = false
    @Volatile var framesReceived = 0L; private set
    private var fb = IntArray(0)
    private var raw = ByteArray(0)
    private var thread: Thread? = null
    @Volatile var minFrameIntervalMs = 16L

    fun start() {
        running = true
        thread = Thread({ runLoop() }, "rfb-reader").apply { isDaemon = true; start() }
    }

    private fun runLoop() {
        var err: Throwable? = null
        try {
            handshake()
            requestUpdate(false)
            var lastReq = System.currentTimeMillis()
            while (running) {
                when (val type = inp.readUnsignedByte()) {
                    0 -> {
                        readFramebufferUpdate()
                        framesReceived++
                        listener.onUpdateComplete()
                        val now = System.currentTimeMillis()
                        val wait = minFrameIntervalMs - (now - lastReq)
                        if (wait > 0) Thread.sleep(wait)
                        lastReq = System.currentTimeMillis()
                        requestUpdate(true)
                    }
                    1 -> { // SetColourMapEntries (unused in true-colour mode)
                        inp.readUnsignedByte(); inp.readUnsignedShort()
                        val n = inp.readUnsignedShort(); inp.skipBytes(n * 6)
                    }
                    2 -> listener.onBell()
                    3 -> {
                        inp.skipBytes(3)
                        val len = inp.readInt()
                        val b = ByteArray(len); inp.readFully(b)
                        listener.onClipboard(String(b, Charsets.ISO_8859_1))
                    }
                    255 -> readQemuServerMessage()
                    else -> throw IOException("Unknown RFB server message $type")
                }
            }
        } catch (t: Throwable) {
            if (running) err = t
        } finally {
            running = false
            writer.shutdownNow()
            runCatching { conn.close() }
            listener.onDisconnected(err)
        }
    }

    private fun handshake() {
        val ver = ByteArray(12); inp.readFully(ver)
        val v = String(ver, Charsets.US_ASCII)
        if (!v.startsWith("RFB ")) throw IOException("Not an RFB server: $v")
        out.write("RFB 003.008\n".toByteArray()); out.flush()
        val nTypes = inp.readUnsignedByte()
        if (nTypes == 0) {
            val len = inp.readInt(); val r = ByteArray(len); inp.readFully(r)
            throw IOException("VNC server refused connection: ${String(r)}")
        }
        val types = ByteArray(nTypes); inp.readFully(types)
        if (types.none { it.toInt() == 1 }) throw IOException("VNC server requires authentication (unsupported)")
        out.writeByte(1); out.flush()
        if (inp.readInt() != 0) throw IOException("VNC security handshake failed")
        out.writeByte(1); out.flush() // ClientInit: shared
        width = inp.readUnsignedShort(); height = inp.readUnsignedShort()
        inp.skipBytes(16) // server pixel format - we override it below
        val nameLen = inp.readInt(); val name = ByteArray(nameLen); inp.readFully(name)
        serverName = String(name, Charsets.UTF_8)
        allocate(width, height)
        listener.onConnected(serverName, width, height)
        // SetPixelFormat: 32bpp, depth 24, little endian, true colour, R<<16 G<<8 B -> ARGB int
        out.writeByte(0); out.write(ByteArray(3))
        out.writeByte(32); out.writeByte(24); out.writeByte(0); out.writeByte(1)
        out.writeShort(255); out.writeShort(255); out.writeShort(255)
        out.writeByte(16); out.writeByte(8); out.writeByte(0); out.write(ByteArray(3))
        // SetEncodings (first = preferred)
        val enc = mutableListOf(ENC_RAW, ENC_COPYRECT, ENC_DESKTOP_SIZE, ENC_RICH_CURSOR, ENC_QEMU_EXT_KEY)
        if (audio) enc += ENC_QEMU_AUDIO
        out.writeByte(2); out.writeByte(0); out.writeShort(enc.size)
        enc.forEach { out.writeInt(it) }
        out.flush()
    }

    private fun allocate(w: Int, h: Int) {
        width = w; height = h
        fb = IntArray(w * h)
    }

    private fun readFramebufferUpdate() {
        inp.readUnsignedByte()
        val n = inp.readUnsignedShort()
        repeat(n) {
            val x = inp.readUnsignedShort(); val y = inp.readUnsignedShort()
            val w = inp.readUnsignedShort(); val h = inp.readUnsignedShort()
            when (val e = inp.readInt()) {
                ENC_RAW -> readRaw(x, y, w, h)
                ENC_COPYRECT -> {
                    val sx = inp.readUnsignedShort(); val sy = inp.readUnsignedShort()
                    copyRect(sx, sy, x, y, w, h)
                }
                ENC_DESKTOP_SIZE -> { allocate(w, h); listener.onResize(w, h); requestUpdate(false) }
                ENC_RICH_CURSOR -> readCursor(x, y, w, h)
                ENC_QEMU_EXT_KEY -> extKeySupported = true
                ENC_QEMU_AUDIO -> { audioSupported = true; enableAudio() }
                else -> throw IOException("Unsupported encoding $e")
            }
        }
    }

    private fun readRaw(x: Int, y: Int, w: Int, h: Int) {
        val rowBytes = w * 4
        if (raw.size < rowBytes) raw = ByteArray(rowBytes)
        val fbW = width
        for (row in 0 until h) {
            inp.readFully(raw, 0, rowBytes)
            val yy = y + row
            if (yy >= height || x >= fbW) continue
            var o = yy * fbW + x
            var i = 0
            val cols = minOf(w, fbW - x)
            for (c in 0 until cols) {
                fb[o++] = -0x1000000 or ((raw[i + 2].toInt() and 0xFF) shl 16) or
                    ((raw[i + 1].toInt() and 0xFF) shl 8) or (raw[i].toInt() and 0xFF)
                i += 4
            }
        }
        listener.onRect(fb, fbW, x, y, minOf(w, fbW - x), minOf(h, height - y))
    }

    private fun copyRect(sx: Int, sy: Int, dx: Int, dy: Int, w: Int, h: Int) {
        val tmp = IntArray(w * h)
        for (r in 0 until h) System.arraycopy(fb, (sy + r) * width + sx, tmp, r * w, w)
        for (r in 0 until h) System.arraycopy(tmp, r * w, fb, (dy + r) * width + dx, w)
        listener.onRect(fb, width, dx, dy, w, h)
    }

    private fun readCursor(hotX: Int, hotY: Int, w: Int, h: Int) {
        if (w == 0 || h == 0) { listener.onCursor(null, 0, 0, 0, 0); return }
        val px = ByteArray(w * h * 4); inp.readFully(px)
        val maskStride = (w + 7) / 8
        val mask = ByteArray(maskStride * h); inp.readFully(mask)
        val pixels = IntArray(w * h)
        for (yy in 0 until h) for (xx in 0 until w) {
            val i = (yy * w + xx) * 4
            val visible = (mask[yy * maskStride + xx / 8].toInt() shr (7 - xx % 8)) and 1 == 1
            pixels[yy * w + xx] = if (!visible) 0 else -0x1000000 or ((px[i + 2].toInt() and 0xFF) shl 16) or
                ((px[i + 1].toInt() and 0xFF) shl 8) or (px[i].toInt() and 0xFF)
        }
        listener.onCursor(pixels, w, h, hotX, hotY)
    }

    private fun readQemuServerMessage() {
        val sub = inp.readUnsignedByte()
        if (sub != 1) throw IOException("Unknown QEMU server sub-message $sub")
        when (inp.readUnsignedShort()) {
            0 -> listener.onAudioStop()
            1 -> listener.onAudioStart()
            2 -> {
                val len = inp.readInt()
                val b = ByteArray(len); inp.readFully(b)
                listener.onAudioData(b, len)
            }
        }
    }

    private fun enableAudio() {
        listener.onAudioFormat(sampleRate, channels)
        send {
            writeByte(255); writeByte(1); writeShort(2) // set format
            writeByte(AUD_FMT_S16); writeByte(channels); writeInt(sampleRate)
            writeByte(255); writeByte(1); writeShort(0) // enable
        }
    }

    fun setAudioEnabled(on: Boolean) {
        if (!audioSupported) return
        send { writeByte(255); writeByte(1); writeShort(if (on) 0 else 1) }
    }

    private fun send(block: DataOutputStream.() -> Unit) {
        if (!running) return
        try {
            writer.execute {
                try { synchronized(out) { out.block(); out.flush() } } catch (_: IOException) { }
            }
        } catch (_: Exception) { }
    }

    fun requestUpdate(incremental: Boolean) = send {
        writeByte(3); writeByte(if (incremental) 1 else 0)
        writeShort(0); writeShort(0); writeShort(width); writeShort(height)
    }

    /** buttons: bit0 left, bit1 middle, bit2 right, bit3/4 wheel up/down, bit5/6 wheel left/right */
    fun pointer(x: Int, y: Int, buttons: Int) = send {
        writeByte(5); writeByte(buttons)
        writeShort(x.coerceIn(0, maxOf(0, width - 1))); writeShort(y.coerceIn(0, maxOf(0, height - 1)))
    }

    fun key(keysym: Int, down: Boolean) = send {
        writeByte(4); writeByte(if (down) 1 else 0); writeShort(0); writeInt(keysym)
    }

    /** QEMU extended key event: PC (XT set 1) scancode, extended keys as 0x80|code. */
    fun keyScancode(keysym: Int, qnum: Int, down: Boolean) {
        if (!extKeySupported || qnum == 0) { key(keysym, down); return }
        send {
            writeByte(255); writeByte(0); writeShort(if (down) 1 else 0)
            writeInt(keysym); writeInt(qnum)
        }
    }

    fun clipboard(text: String) = send {
        val b = text.toByteArray(Charsets.ISO_8859_1)
        writeByte(6); write(ByteArray(3)); writeInt(b.size); write(b)
    }

    fun close() {
        running = false
        runCatching { conn.close() }
        writer.shutdown()
        runCatching { writer.awaitTermination(200, TimeUnit.MILLISECONDS) }
    }

    val isRunning get() = running
}
