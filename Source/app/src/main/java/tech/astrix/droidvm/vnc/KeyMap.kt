package tech.astrix.droidvm.vnc

import android.view.KeyEvent

/**
 * Android key codes -> X11 keysym + PC scancode (QEMU "qnum": XT set 1, extended keys 0x80|code).
 * Scancodes make the guest's own keyboard layout apply (physical keyboards, special keys);
 * keysyms are used for text coming from the on-screen IME.
 */
object KeyMap {
    data class K(val keysym: Int, val qnum: Int)

    const val XK_Shift_L = 0xffe1
    const val XK_Control_L = 0xffe3
    const val XK_Alt_L = 0xffe9
    const val XK_Super_L = 0xffeb
    const val XK_Return = 0xff0d
    const val XK_BackSpace = 0xff08
    const val XK_Tab = 0xff09
    const val XK_Escape = 0xff1b
    const val XK_Delete = 0xffff

    private val map = HashMap<Int, K>().apply {
        // letters
        val letters = "qwertyuiop" to 0x10; val l2 = "asdfghjkl" to 0x1e; val l3 = "zxcvbnm" to 0x2c
        for ((row, start) in listOf(letters, l2, l3)) row.forEachIndexed { i, c ->
            put(KeyEvent.KEYCODE_A + (c - 'a'), K(c.code, start + i))
        }
        // digits 1..9,0
        "1234567890".forEachIndexed { i, c -> put(KeyEvent.KEYCODE_0 + (c - '0'), K(c.code, 0x02 + i)) }
        put(KeyEvent.KEYCODE_ESCAPE, K(XK_Escape, 0x01))
        put(KeyEvent.KEYCODE_MINUS, K('-'.code, 0x0c)); put(KeyEvent.KEYCODE_EQUALS, K('='.code, 0x0d))
        put(KeyEvent.KEYCODE_DEL, K(XK_BackSpace, 0x0e)); put(KeyEvent.KEYCODE_TAB, K(XK_Tab, 0x0f))
        put(KeyEvent.KEYCODE_LEFT_BRACKET, K('['.code, 0x1a)); put(KeyEvent.KEYCODE_RIGHT_BRACKET, K(']'.code, 0x1b))
        put(KeyEvent.KEYCODE_ENTER, K(XK_Return, 0x1c)); put(KeyEvent.KEYCODE_CTRL_LEFT, K(XK_Control_L, 0x1d))
        put(KeyEvent.KEYCODE_SEMICOLON, K(';'.code, 0x27)); put(KeyEvent.KEYCODE_APOSTROPHE, K('\''.code, 0x28))
        put(KeyEvent.KEYCODE_GRAVE, K('`'.code, 0x29)); put(KeyEvent.KEYCODE_SHIFT_LEFT, K(XK_Shift_L, 0x2a))
        put(KeyEvent.KEYCODE_BACKSLASH, K('\\'.code, 0x2b)); put(KeyEvent.KEYCODE_COMMA, K(','.code, 0x33))
        put(KeyEvent.KEYCODE_PERIOD, K('.'.code, 0x34)); put(KeyEvent.KEYCODE_SLASH, K('/'.code, 0x35))
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, K(0xffe2, 0x36)); put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, K(0xffaa, 0x37))
        put(KeyEvent.KEYCODE_ALT_LEFT, K(XK_Alt_L, 0x38)); put(KeyEvent.KEYCODE_SPACE, K(0x20, 0x39))
        put(KeyEvent.KEYCODE_CAPS_LOCK, K(0xffe5, 0x3a))
        for (i in 0 until 10) put(KeyEvent.KEYCODE_F1 + i, K(0xffbe + i, 0x3b + i))
        put(KeyEvent.KEYCODE_F11, K(0xffc8, 0x57)); put(KeyEvent.KEYCODE_F12, K(0xffc9, 0x58))
        put(KeyEvent.KEYCODE_NUM_LOCK, K(0xff7f, 0x45)); put(KeyEvent.KEYCODE_SCROLL_LOCK, K(0xff14, 0x46))
        put(KeyEvent.KEYCODE_NUMPAD_7, K(0xffb7, 0x47)); put(KeyEvent.KEYCODE_NUMPAD_8, K(0xffb8, 0x48))
        put(KeyEvent.KEYCODE_NUMPAD_9, K(0xffb9, 0x49)); put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, K(0xffad, 0x4a))
        put(KeyEvent.KEYCODE_NUMPAD_4, K(0xffb4, 0x4b)); put(KeyEvent.KEYCODE_NUMPAD_5, K(0xffb5, 0x4c))
        put(KeyEvent.KEYCODE_NUMPAD_6, K(0xffb6, 0x4d)); put(KeyEvent.KEYCODE_NUMPAD_ADD, K(0xffab, 0x4e))
        put(KeyEvent.KEYCODE_NUMPAD_1, K(0xffb1, 0x4f)); put(KeyEvent.KEYCODE_NUMPAD_2, K(0xffb2, 0x50))
        put(KeyEvent.KEYCODE_NUMPAD_3, K(0xffb3, 0x51)); put(KeyEvent.KEYCODE_NUMPAD_0, K(0xffb0, 0x52))
        put(KeyEvent.KEYCODE_NUMPAD_DOT, K(0xffae, 0x53))
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, K(0xff8d, 0x9c)); put(KeyEvent.KEYCODE_CTRL_RIGHT, K(0xffe4, 0x9d))
        put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, K(0xffaf, 0xb5)); put(KeyEvent.KEYCODE_SYSRQ, K(0xff61, 0xb7))
        put(KeyEvent.KEYCODE_ALT_RIGHT, K(0xffea, 0xb8)); put(KeyEvent.KEYCODE_MOVE_HOME, K(0xff50, 0xc7))
        put(KeyEvent.KEYCODE_DPAD_UP, K(0xff52, 0xc8)); put(KeyEvent.KEYCODE_PAGE_UP, K(0xff55, 0xc9))
        put(KeyEvent.KEYCODE_DPAD_LEFT, K(0xff51, 0xcb)); put(KeyEvent.KEYCODE_DPAD_RIGHT, K(0xff53, 0xcd))
        put(KeyEvent.KEYCODE_MOVE_END, K(0xff57, 0xcf)); put(KeyEvent.KEYCODE_DPAD_DOWN, K(0xff54, 0xd0))
        put(KeyEvent.KEYCODE_PAGE_DOWN, K(0xff56, 0xd1)); put(KeyEvent.KEYCODE_INSERT, K(0xff63, 0xd2))
        put(KeyEvent.KEYCODE_FORWARD_DEL, K(XK_Delete, 0xd3)); put(KeyEvent.KEYCODE_META_LEFT, K(XK_Super_L, 0xdb))
        put(KeyEvent.KEYCODE_META_RIGHT, K(0xffec, 0xdc)); put(KeyEvent.KEYCODE_MENU, K(0xff67, 0xdd))
        put(KeyEvent.KEYCODE_BREAK, K(0xff13, 0xc6))
    }

    fun forKeyCode(code: Int): K? = map[code]

    /** Characters that need Shift on a US layout (QEMU's default en-us keymap for keysym events). */
    private const val SHIFTED = "~!@#\$%^&*()_+{}|:\"<>?"

    /** Converts a text character from the IME to (keysym, needsShift). Non-Latin-1 uses Unicode keysyms. */
    fun forChar(c: Char): Pair<Int, Boolean> = when {
        c == '\n' -> XK_Return to false
        c == '\t' -> XK_Tab to false
        c in 'A'..'Z' -> c.code to true
        c in SHIFTED -> c.code to true
        c.code in 0x20..0xff -> c.code to false
        else -> (0x01000000 or c.code) to false
    }
}
