package tech.astrix.droidvm.display

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import tech.astrix.droidvm.engine.LocalSocketConnection
import tech.astrix.droidvm.vnc.KeyMap
import tech.astrix.droidvm.vnc.RfbClient
import tech.astrix.droidvm.vnc.RfbListener
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Plays the guest's PCM audio (from the QEMU VNC audio extension) through AudioTrack. */
class AudioPlayer {
    private var track: AudioTrack? = null
    private val queue = ArrayBlockingQueue<ByteArray>(48)
    @Volatile private var running = true
    @Volatile var volume = 1f
        set(v) { field = v; runCatching { track?.setVolume(v) } }
    private var thread: Thread? = null

    fun configure(rate: Int, channels: Int) {
        release()
        running = true
        val ch = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(rate, ch, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(ch)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min * 4, 32768))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.setVolume(volume); it.play() }
        thread = Thread({
            while (running) {
                val b = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                track?.write(b, 0, b.size)
            }
        }, "vm-audio").apply { isDaemon = true; start() }
    }

    fun push(data: ByteArray) {
        if (!queue.offer(data)) { queue.poll(); queue.offer(data) } // drop oldest when lagging
    }

    fun pause() { runCatching { track?.pause(); track?.flush() }; queue.clear() }
    fun resume() { runCatching { track?.play() } }

    fun release() {
        running = false
        thread?.join(300)
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        queue.clear()
    }
}

/**
 * Connects to the VM's private VNC unix socket and owns the framebuffer bitmap.
 */
class VncSession(private val socketPath: String, private val callbacks: Callbacks) : RfbListener {
    interface Callbacks {
        fun onFrame()
        fun onResized(w: Int, h: Int)
        fun onCursorChanged()
        fun onState(connected: Boolean, error: String?)
        fun onClipboard(text: String) {}
    }

    private val main = Handler(Looper.getMainLooper())
    val lock = Any()
    var bitmap: Bitmap? = null; private set
    var cursor: Bitmap? = null; private set
    var cursorHotX = 0; private set
    var cursorHotY = 0; private set
    @Volatile var client: RfbClient? = null; private set
    val audio = AudioPlayer()
    @Volatile var connected = false; private set
    @Volatile var closed = false; private set

    val fbWidth get() = client?.width ?: 0
    val fbHeight get() = client?.height ?: 0

    fun connect(): Boolean {
        if (closed) return false
        if (client != null) return true // a connection attempt / session is already active
        return try {
            val c = RfbClient(LocalSocketConnection(socketPath), this)
            client = c
            c.start()
            true
        } catch (e: Exception) {
            client = null
            false
        }
    }

    // ---- RfbListener (RFB thread) ----
    override fun onConnected(name: String, width: Int, height: Int) {
        connected = true
        newBitmap(width, height)
        main.post { callbacks.onResized(width, height); callbacks.onState(true, null) }
    }

    override fun onResize(width: Int, height: Int) {
        newBitmap(width, height)
        main.post { callbacks.onResized(width, height) }
    }

    private fun newBitmap(w: Int, h: Int) = synchronized(lock) {
        bitmap = Bitmap.createBitmap(maxOf(1, w), maxOf(1, h), Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }
    }

    override fun onRect(fb: IntArray, fbWidth: Int, x: Int, y: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        synchronized(lock) {
            val b = bitmap ?: return
            if (x + w <= b.width && y + h <= b.height) b.setPixels(fb, y * fbWidth + x, fbWidth, x, y, w, h)
        }
    }

    override fun onUpdateComplete() { callbacks.onFrame() }

    override fun onCursor(pixels: IntArray?, w: Int, h: Int, hotX: Int, hotY: Int) {
        synchronized(lock) {
            cursor = pixels?.let { Bitmap.createBitmap(it, w, h, Bitmap.Config.ARGB_8888) }
            cursorHotX = hotX; cursorHotY = hotY
        }
        main.post { callbacks.onCursorChanged() }
    }

    override fun onAudioFormat(sampleRate: Int, channels: Int) { audio.configure(sampleRate, channels) }
    override fun onAudioStart() { audio.resume() }
    override fun onAudioData(data: ByteArray, len: Int) { audio.push(data) }
    override fun onAudioStop() { audio.pause() }
    override fun onClipboard(text: String) { main.post { callbacks.onClipboard(text) } }

    override fun onDisconnected(error: Throwable?) {
        connected = false
        client = null
        audio.release()
        main.post { callbacks.onState(false, error?.message) }
    }

    // ---- input ----
    private var buttons = 0
    var pointerX = 0; private set
    var pointerY = 0; private set

    fun movePointer(x: Int, y: Int) {
        pointerX = x.coerceIn(0, maxOf(0, fbWidth - 1)); pointerY = y.coerceIn(0, maxOf(0, fbHeight - 1))
        client?.pointer(pointerX, pointerY, buttons)
    }
    fun setButtons(mask: Int) { buttons = mask; client?.pointer(pointerX, pointerY, buttons) }
    fun buttonDown(b: Int) = setButtons(buttons or b)
    fun buttonUp(b: Int) = setButtons(buttons and b.inv())
    fun click(b: Int) { buttonDown(b); buttonUp(b) }
    fun scroll(dy: Int, dx: Int = 0) {
        val c = client ?: return
        val v = if (dy < 0) 8 else 16
        repeat(kotlin.math.abs(dy)) { c.pointer(pointerX, pointerY, buttons or v); c.pointer(pointerX, pointerY, buttons) }
        val hb = if (dx < 0) 32 else 64
        repeat(kotlin.math.abs(dx)) { c.pointer(pointerX, pointerY, buttons or hb); c.pointer(pointerX, pointerY, buttons) }
    }

    fun key(k: KeyMap.K, down: Boolean) { client?.keyScancode(k.keysym, k.qnum, down) }
    fun keysym(sym: Int, down: Boolean) { client?.key(sym, down) }

    fun typeText(text: CharSequence) {
        val c = client ?: return
        for (ch in text) {
            val (sym, shift) = KeyMap.forChar(ch)
            if (shift) c.key(KeyMap.XK_Shift_L, true)
            c.key(sym, true); c.key(sym, false)
            if (shift) c.key(KeyMap.XK_Shift_L, false)
        }
    }

    fun sendCombo(vararg keys: KeyMap.K) {
        keys.forEach { key(it, true) }
        keys.reversed().forEach { key(it, false) }
    }

    fun close() {
        closed = true
        client?.close()
        audio.release()
    }
}
