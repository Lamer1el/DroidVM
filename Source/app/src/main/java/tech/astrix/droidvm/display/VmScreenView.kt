package tech.astrix.droidvm.display

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import tech.astrix.droidvm.vnc.KeyMap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class ScaleMode(val label: String) { FIT("Fit"), FILL("Fill"), STRETCH("Stretch"), ORIGINAL("1:1") }
enum class TouchMode(val label: String) { TOUCHPAD("Touchpad"), DIRECT("Direct touch") }

/**
 * Renders the VM framebuffer and converts touch / mouse / keyboard / IME input into RFB events.
 */
@SuppressLint("ViewConstructor")
class VmScreenView(context: Context, private val session: VncSession) : View(context) {

    var scaleMode = ScaleMode.FIT; set(v) { field = v; resetZoom() }
    var touchMode = TouchMode.TOUCHPAD
    var smoothScaling = true
    /** Sticky modifiers from the on-screen key bar (applied to the next key, then released). */
    val stickyModifiers = linkedSetOf<KeyMap.K>()
    var onStickyChanged: () -> Unit = {}

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val inverse = Matrix()
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private val dst = RectF()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.BLACK)
    }

    fun resetZoom() { zoom = 1f; panX = 0f; panY = 0f; invalidate() }

    // ------------------------------------------------------------ geometry
    private fun computeMatrix() {
        val fw = session.fbWidth.toFloat(); val fh = session.fbHeight.toFloat()
        if (fw <= 0 || fh <= 0 || width == 0 || height == 0) { matrix.reset(); return }
        var sx: Float; var sy: Float
        when (scaleMode) {
            ScaleMode.FIT -> { sx = min(width / fw, height / fh); sy = sx }
            ScaleMode.FILL -> { sx = max(width / fw, height / fh); sy = sx }
            ScaleMode.STRETCH -> { sx = width / fw; sy = height / fh }
            ScaleMode.ORIGINAL -> { sx = 1f; sy = 1f }
        }
        sx *= zoom; sy *= zoom
        val cw = fw * sx; val ch = fh * sy
        // clamp panning so the image never leaves the view more than necessary
        val maxPanX = max(0f, (cw - width) / 2f); val maxPanY = max(0f, (ch - height) / 2f)
        panX = panX.coerceIn(-maxPanX, maxPanX); panY = panY.coerceIn(-maxPanY, maxPanY)
        val tx = (width - cw) / 2f + panX; val ty = (height - ch) / 2f + panY
        matrix.setScale(sx, sy); matrix.postTranslate(tx, ty)
        matrix.invert(inverse)
        dst.set(tx, ty, tx + cw, ty + ch)
    }

    private val pt = FloatArray(2)
    private fun toFb(x: Float, y: Float): Pair<Int, Int> {
        pt[0] = x; pt[1] = y; inverse.mapPoints(pt)
        return pt[0].toInt() to pt[1].toInt()
    }
    private fun fbScale(): Float { val v = FloatArray(9); matrix.getValues(v); return v[Matrix.MSCALE_X].takeIf { it > 0 } ?: 1f }

    // ------------------------------------------------------------ drawing
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.WHITE }
    private val ringShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = 0x88000000.toInt() }
    private val arrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val arrowStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f }
    private val arrow = Path().apply { moveTo(0f, 0f); lineTo(0f, 22f); lineTo(6f, 16f); lineTo(10f, 25f); lineTo(14f, 23f); lineTo(10f, 15f); lineTo(17f, 15f); close() }

    override fun onDraw(canvas: Canvas) {
        computeMatrix()
        paint.isFilterBitmap = smoothScaling
        synchronized(session.lock) {
            val b = session.bitmap ?: return
            canvas.drawBitmap(b, matrix, paint)
            val px = session.pointerX.toFloat(); val py = session.pointerY.toFloat()
            pt[0] = px; pt[1] = py; matrix.mapPoints(pt)
            val c = session.cursor
            if (c != null) {
                canvas.save(); canvas.concat(matrix)
                canvas.drawBitmap(c, px - session.cursorHotX, py - session.cursorHotY, paint)
                canvas.restore()
            } else if (touchMode == TouchMode.TOUCHPAD && session.connected) {
                // Guest draws its own (software) cursor; we add an unobtrusive locator ring + arrow.
                canvas.save(); canvas.translate(pt[0], pt[1])
                canvas.drawPath(arrow, arrowFill); canvas.drawPath(arrow, arrowStroke)
                canvas.restore()
            }
        }
    }

    fun requestFrame() = postInvalidateOnAnimation()

    // ------------------------------------------------------------ touch gestures
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoom = (zoom * d.scaleFactor).coerceIn(1f, 6f); invalidate(); return true
        }
    })

    private var downX = 0f; private var downY = 0f; private var lastX = 0f; private var lastY = 0f
    private var downTime = 0L; private var lastTapUp = 0L
    private var maxPointers = 0; private var moved = false; private var dragging = false
    private var twoFingerLastY = 0f; private var twoFingerLastX = 0f; private var scrollAccum = 0f
    private var longPressFired = false
    private val touchSlop = 12f * resources.displayMetrics.density
    private val longPress = Runnable {
        if (!moved && maxPointers == 1) { longPressFired = true; session.click(4); performHapticFeedback(0) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_MOUSE) && e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) return handleMouse(e)
        scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; downTime = e.eventTime
                maxPointers = 1; moved = false; longPressFired = false; scrollAccum = 0f
                // double-tap-and-drag => hold left button
                dragging = touchMode == TouchMode.TOUCHPAD && e.eventTime - lastTapUp < 250
                if (touchMode == TouchMode.DIRECT) {
                    val (fx, fy) = toFb(e.x, e.y); session.movePointer(fx, fy)
                }
                if (dragging) session.buttonDown(1)
                postDelayed(longPress, 550)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = max(maxPointers, e.pointerCount)
                removeCallbacks(longPress)
                if (e.pointerCount == 2) { twoFingerLastY = (e.getY(0) + e.getY(1)) / 2; twoFingerLastX = (e.getX(0) + e.getX(1)) / 2 }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && hypot(e.x - downX, e.y - downY) > touchSlop) { moved = true; removeCallbacks(longPress) }
                if (e.pointerCount >= 2) {
                    val cy = (e.getY(0) + e.getY(1)) / 2; val cx = (e.getX(0) + e.getX(1)) / 2
                    if (!scaleDetector.isInProgress || abs(scaleDetector.scaleFactor - 1f) < 0.01f) {
                        if (zoom > 1.01f) { panX += cx - twoFingerLastX; panY += cy - twoFingerLastY; invalidate() }
                        else {
                            scrollAccum += cy - twoFingerLastY
                            val step = 40f * resources.displayMetrics.density / 2
                            while (abs(scrollAccum) > step) {
                                session.scroll(if (scrollAccum > 0) -1 else 1); scrollAccum -= if (scrollAccum > 0) step else -step
                            }
                        }
                    }
                    twoFingerLastY = cy; twoFingerLastX = cx; moved = true
                } else if (moved && maxPointers == 1) {
                    when (touchMode) {
                        TouchMode.TOUCHPAD -> {
                            val dx = e.x - lastX; val dy = e.y - lastY
                            val speed = hypot(dx, dy)
                            val accel = (1f + speed / 25f).coerceAtMost(3f) / fbScale()
                            session.movePointer((session.pointerX + dx * accel).toInt(), (session.pointerY + dy * accel).toInt())
                            invalidate()
                        }
                        TouchMode.DIRECT -> {
                            if (!dragging) { dragging = true; session.buttonDown(1) }
                            val (fx, fy) = toFb(e.x, e.y); session.movePointer(fx, fy)
                        }
                    }
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val quick = e.eventTime - downTime < 300
                if (dragging) { session.buttonUp(1); dragging = false }
                else if (!moved && !longPressFired && maxPointers == 1 && quick) {
                    session.click(1); lastTapUp = e.eventTime
                } else if (!moved && maxPointers == 2 && quick) session.click(4)
                else if (!moved && maxPointers >= 3 && quick) session.click(2)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); if (dragging) session.buttonUp(1); dragging = false }
        }
        return true
    }

    // ------------------------------------------------------------ physical / Bluetooth mouse
    private fun mouseButtons(e: MotionEvent): Int {
        var m = 0
        if (e.buttonState and MotionEvent.BUTTON_PRIMARY != 0) m = m or 1
        if (e.buttonState and MotionEvent.BUTTON_TERTIARY != 0) m = m or 2
        if (e.buttonState and MotionEvent.BUTTON_SECONDARY != 0) m = m or 4
        return m
    }

    private fun handleMouse(e: MotionEvent): Boolean {
        val (fx, fy) = toFb(e.x, e.y)
        session.movePointer(fx, fy)
        session.setButtons(mouseButtons(e))
        invalidate()
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            when (e.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> {
                    val (fx, fy) = toFb(e.x, e.y); session.movePointer(fx, fy); invalidate(); return true
                }
                MotionEvent.ACTION_SCROLL -> {
                    val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL); val h = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
                    session.scroll(if (v > 0) -1 else if (v < 0) 1 else 0, if (h > 0) 1 else if (h < 0) -1 else 0)
                    return true
                }
                MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                    session.setButtons(mouseButtons(e)); return true
                }
            }
        }
        return super.onGenericMotionEvent(e)
    }

    // ------------------------------------------------------------ keyboard
    private fun isSystemKey(code: Int) = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN ||
        code == KeyEvent.KEYCODE_VOLUME_MUTE || code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_HOME

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = handleKey(event, true) || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = handleKey(event, false) || super.onKeyUp(keyCode, event)

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN && event.characters != null) { session.typeText(event.characters); return true }
        return super.onKeyMultiple(keyCode, repeatCount, event)
    }

    private fun handleKey(e: KeyEvent, down: Boolean): Boolean {
        val code = e.keyCode
        if (isSystemKey(code)) return false
        // A mouse right-click is delivered as BACK by Android; never forward it as a key.
        if (code == KeyEvent.KEYCODE_BACK) {
            return e.isFromSource(InputDevice.SOURCE_MOUSE)
        }
        val k = KeyMap.forKeyCode(code)
        if (k != null) {
            if (down && e.repeatCount == 0) stickyModifiers.forEach { session.key(it, true) }
            session.key(k, down)
            if (!down && stickyModifiers.isNotEmpty() && !isModifier(code)) {
                stickyModifiers.reversed().forEach { session.key(it, false) }
                stickyModifiers.clear(); onStickyChanged()
            }
            return true
        }
        val uc = e.unicodeChar
        if (uc != 0 && down) { session.typeText(uc.toChar().toString()); return true }
        return false
    }

    private fun isModifier(code: Int) = code in setOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT)

    /** Sends a key from the on-screen key bar, applying sticky modifiers. */
    fun tapKey(k: KeyMap.K) {
        stickyModifiers.forEach { session.key(it, true) }
        session.key(k, true); session.key(k, false)
        stickyModifiers.reversed().forEach { session.key(it, false) }
        if (stickyModifiers.isNotEmpty()) { stickyModifiers.clear(); onStickyChanged() }
    }

    // ------------------------------------------------------------ on-screen keyboard (IME)
    fun showKeyboard() {
        requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }
    fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            private var composing = ""
            private fun backspace(n: Int) = repeat(n) { session.sendCombo(KeyMap.K(KeyMap.XK_BackSpace, 0x0e)) }
            private fun applyText(t: String) {
                if (stickyModifiers.isNotEmpty() && t.length == 1) {
                    val ch = t[0].lowercaseChar()
                    val code = if (ch in 'a'..'z') KeyEvent.KEYCODE_A + (ch - 'a') else -1
                    KeyMap.forKeyCode(code)?.let { tapKey(it); return }
                }
                session.typeText(t)
            }
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                val t = text.toString()
                if (composing.isNotEmpty()) {
                    val common = t.commonPrefixWith(composing)
                    backspace(composing.length - common.length); applyText(t.substring(common.length))
                    composing = ""
                } else applyText(t)
                return true
            }
            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                val t = text.toString()
                val common = t.commonPrefixWith(composing)
                backspace(composing.length - common.length)
                session.typeText(t.substring(common.length))
                composing = t
                return true
            }
            override fun finishComposingText(): Boolean { composing = ""; return true }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                backspace(max(1, beforeLength)); return true
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                handleKey(event, event.action == KeyEvent.ACTION_DOWN); return true
            }
        }
    }
}
