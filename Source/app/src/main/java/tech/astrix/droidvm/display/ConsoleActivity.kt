package tech.astrix.droidvm.display

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.astrix.droidvm.DroidVmApp
import tech.astrix.droidvm.engine.LocalSocketConnection
import tech.astrix.droidvm.engine.StreamConnection
import tech.astrix.droidvm.engine.VmManager
import tech.astrix.droidvm.engine.VmRuntime
import tech.astrix.droidvm.engine.VmState
import tech.astrix.droidvm.i18n.tr
import tech.astrix.droidvm.ui.StateChip
import tech.astrix.droidvm.ui.Text
import tech.astrix.droidvm.ui.theme.DroidVmTheme
import java.io.File
import java.io.RandomAccessFile

/**
 * Text console of a VM. Can always be opened ("force open"), even when the VM has no working graphics:
 *  - Serial (ttyS0): live connection to QEMU's serial port over a private unix socket; when the VM is
 *    not running the last serial log is shown read-only;
 *  - QEMU monitor: human monitor commands through QMP (info status, sendkey, ...).
 * Keys on the key row repeat while they are held down; Ctrl / Alt can be latched (held) for the next key.
 */
class ConsoleActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_ID = "vmId"
        fun intent(ctx: Context, id: String) = Intent(ctx, ConsoleActivity::class.java).putExtra(EXTRA_ID, id)
    }

    private lateinit var vmId: String
    private val term = MiniTerminal(4000)
    private val tick = mutableIntStateOf(0)
    private val connected = mutableStateOf(false)
    @Volatile private var conn: StreamConnection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        vmId = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        val vm = DroidVmApp.repo.vm(vmId) ?: run { finish(); return }
        if (DroidVmApp.repo.settings.value.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        loadLogTail()
        setContent { DroidVmTheme(2, false) { ConsoleUi(vm.name) } }
    }

    private fun loadLogTail() {
        val f = VmManager.paths(vmId).serialLog
        if (!f.exists()) return
        runCatching {
            RandomAccessFile(f, "r").use { raf ->
                val n = minOf(raf.length(), 64 * 1024L)
                raf.seek(raf.length() - n)
                val b = ByteArray(n.toInt()); raf.readFully(b)
                term.feed(String(b, Charsets.UTF_8))
            }
            tick.intValue++
        }
    }

    private fun send(bytes: ByteArray) {
        val c = conn ?: return
        Thread { runCatching { c.output.write(bytes); c.output.flush() } }.start()
    }
    private fun send(s: String) = send(s.toByteArray(Charsets.UTF_8))

    /** Reader loop: (re)connects to console.sock while the VM is alive. */
    private suspend fun serialLoop(isAlive: () -> Boolean) = withContext(Dispatchers.IO) {
        val sock = VmManager.paths(vmId).console
        var first = true
        while (isActive) {
            if (!isAlive() || !sock.exists()) { delay(700); continue }
            val c = runCatching { LocalSocketConnection(sock.absolutePath) }.getOrNull()
            if (c == null) { delay(700); continue }
            conn = c; connected.value = true
            if (first) { term.feed("\n\u001b[0m--- ${tr("connected to serial console ttyS0")} ---\n"); tick.intValue++; first = false }
            // wake up a getty that is waiting for input
            send("\r")
            val buf = ByteArray(8192)
            try {
                while (isActive) {
                    val n = c.input.read(buf); if (n < 0) break
                    term.feed(String(buf, 0, n, Charsets.UTF_8)); tick.intValue++
                }
            } catch (_: Exception) {}
            runCatching { c.close() }
            conn = null; connected.value = false
            delay(700)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ConsoleUi(name: String) {
        val states by VmManager.states.collectAsStateWithLifecycle()
        val rt = states[vmId] ?: VmRuntime(VmState.STOPPED)
        val alive = rt.state in setOf(VmState.STARTING, VmState.RUNNING, VmState.PAUSED)
        val scope = rememberCoroutineScope()
        var tab by remember { mutableIntStateOf(0) }
        var input by remember { mutableStateOf("") }
        var fontSp by remember { mutableIntStateOf(12) }
        var ctrl by remember { mutableStateOf(false) }
        var alt by remember { mutableStateOf(false) }
        val monitorOut = remember { mutableStateListOf<String>() }
        val isConnected by connected
        @Suppress("UNUSED_VARIABLE") val t = tick.intValue
        val aliveState = remember { mutableStateOf(alive) }
        aliveState.value = alive

        LaunchedEffect(Unit) { serialLoop { aliveState.value } }
        DisposableEffect(Unit) { onDispose { runCatching { conn?.close() } } }

        fun keyBytes(s: String): String {
            var out = s
            if (ctrl) { out = out.map { ch -> val c = ch.uppercaseChar(); if (c in '@'..'_') (c.code - 64).toChar() else if (ch == '?') 127.toChar() else ch }.joinToString(""); ctrl = false }
            if (alt) { out = "\u001b" + out; alt = false }
            return out
        }
        fun submit() {
            if (tab == 0) { val latched = ctrl && input.isNotEmpty(); send(keyBytes(input) + if (latched) "" else "\r"); input = "" }
            else {
                val cmd = input.trim(); if (cmd.isEmpty()) return
                input = ""; monitorOut += "(qemu) $cmd"
                scope.launch {
                    val r = VmManager.monitor(vmId, cmd)
                    monitorOut += r.fold({ it.trimEnd().ifEmpty { "OK" } }, { tr("Error") + ": " + it.message })
                }
            }
        }

        Scaffold(
            containerColor = Color(0xFF0B0F0A),
            topBar = {
                TopAppBar(
                    title = { Column { Text(name, maxLines = 1); Text(tr("Console"), style = MaterialTheme.typography.labelSmall) } },
                    navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } },
                    actions = {
                        StateChip(rt.state)
                        IconButton(onClick = { fontSp = (fontSp - 1).coerceAtLeast(8) }) { Icon(Icons.Filled.TextDecrease, tr("Smaller text")) }
                        IconButton(onClick = { fontSp = (fontSp + 1).coerceAtMost(22) }) { Icon(Icons.Filled.TextIncrease, tr("Larger text")) }
                        IconButton(onClick = {
                            val text = if (tab == 0) term.text() else monitorOut.joinToString("\n")
                            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("console", text))
                        }) { Icon(Icons.Filled.ContentCopy, tr("Copy all")) }
                        if (alive) IconButton(onClick = { startActivity(DisplayActivity.intent(this@ConsoleActivity, vmId)) }) {
                            Icon(Icons.Filled.DesktopWindows, tr("Open display"))
                        }
                    },
                )
            },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(tab == 0, { tab = 0 }, text = { Text(tr("Serial (ttyS0)")) })
                    Tab(tab == 1, { tab = 1 }, text = { Text(tr("QEMU monitor")) })
                }
                val status = when {
                    !alive -> tr("VM is not running - showing the last serial output (read-only).")
                    tab == 0 && !isConnected -> tr("Connecting to the serial console…")
                    tab == 0 -> tr("Tip: the guest needs console=ttyS0 on the kernel command line (or a getty on ttyS0) to show a login here.")
                    else -> tr("Monitor commands, e.g. info status, info network, info block, sendkey ctrl-alt-delete")
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB5C9AE), modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))

                val lines = if (tab == 0) term.lines() else monitorOut.toList()
                val listState = rememberLazyListState()
                var follow by remember { mutableStateOf(true) }
                LaunchedEffect(listState.isScrollInProgress) { if (!listState.isScrollInProgress) follow = !listState.canScrollForward }
                LaunchedEffect(lines.size, tab, t) { if (lines.isNotEmpty() && follow) listState.scrollToItem(lines.lastIndex) }
                SelectionContainer(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
                        itemsIndexed(lines) { _, l ->
                            Text(l.ifEmpty { " " }, fontFamily = FontFamily.Monospace, fontSize = fontSp.sp, color = Color(0xFFD7F5C8), softWrap = true)
                        }
                    }
                }
                if (tab == 0) KeyRow(enabled = alive && isConnected, ctrl = ctrl, alt = alt, onCtrl = { ctrl = !ctrl }, onAlt = { alt = !alt }) { send(keyBytes(it)) }
                Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(input, { input = it }, modifier = Modifier.weight(1f), singleLine = true, enabled = alive,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = Color.White),
                        placeholder = { Text(if (tab == 0) tr("Type a command, Enter sends it") else "info status") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Send, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onSend = { submit() }))
                    IconButton(onClick = { submit() }, enabled = alive) { Icon(Icons.AutoMirrored.Filled.Send, tr("Send")) }
                }
            }
        }
    }

    @Composable
    private fun KeyRow(enabled: Boolean, ctrl: Boolean, alt: Boolean, onCtrl: () -> Unit, onAlt: () -> Unit, onKey: (String) -> Unit) {
        val keys = listOf("Esc" to "\u001b", "Tab" to "\t", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C",
            "Home" to "\u001b[H", "End" to "\u001b[F", "PgUp" to "\u001b[5~", "PgDn" to "\u001b[6~", "⌫" to "\u007f", "Enter" to "\r",
            "^C" to "\u0003", "^D" to "\u0004", "^Z" to "\u001a", "^L" to "\u000c", "^R" to "\u0012", "|" to "|", "~" to "~", "/" to "/", "-" to "-")
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(ctrl, { onCtrl() }, label = { Text("Ctrl") }, enabled = enabled)
                FilterChip(alt, { onAlt() }, label = { Text("Alt") }, enabled = enabled)
                keys.forEach { (label, seq) -> RepeatKey(label, enabled) { onKey(seq) } }
            }
        }
    }
}

/** A key that fires once on press and then auto-repeats while it is held down. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RepeatKey(label: String, enabled: Boolean = true, highlighted: Boolean = false, onPress: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = when { pressed || highlighted -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.surfaceContainerHighest },
        contentColor = if (pressed || highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(width = 52.dp, height = 40.dp).pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pressed = true
                onPress()
                val job = scope.launch { delay(400); while (isActive) { onPress(); delay(60) } }
                waitForUpOrCancellation()
                job.cancel(); pressed = false
            }
        },
    ) { Box(contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.labelLarge) } }
}

/**
 * Very small line-oriented terminal: handles CR/LF, backspace, tabs and strips ANSI/VT100 escape
 * sequences (colors, cursor movement, erase). Good for boot logs and shells; full-screen programs
 * (vim, htop) are shown as plain text.
 */
class MiniTerminal(private val maxLines: Int) {
    private val done = ArrayDeque<String>()
    private val cur = StringBuilder()
    private var col = 0
    private var esc = 0 // 0 none, 1 after ESC, 2 CSI, 3 OSC
    private val csi = StringBuilder()

    @Synchronized fun feed(s: String) {
        for (ch in s) {
            when (esc) {
                1 -> { esc = when (ch) { '[' -> 2; ']' -> 3; else -> 0 }; csi.clear(); continue }
                2 -> { if (ch.code in 0x40..0x7e) { esc = 0; csiFinal(ch) } else csi.append(ch); continue }
                3 -> { if (ch == '\u0007' || ch == '\u001b') esc = if (ch == '\u001b') 1 else 0; continue }
            }
            when (ch) {
                '\u001b' -> esc = 1
                '\n' -> newline()
                '\r' -> col = 0
                '\b' -> if (col > 0) col--
                '\t' -> repeat(8 - col % 8) { put(' ') }
                '\u0007', '\u0000' -> {}
                else -> if (ch.code >= 32 || ch.code == 0x7f) { if (ch.code != 0x7f) put(ch) }
            }
        }
    }

    private fun csiFinal(f: Char) {
        when (f) {
            'K' -> if (csi.toString().let { it.isEmpty() || it == "0" }) cur.setLength(col.coerceAtMost(cur.length)) else if (csi.toString() == "2") { cur.setLength(0); col = 0 }
            'J' -> if (csi.toString() == "2" || csi.toString() == "3") { done.clear(); cur.setLength(0); col = 0 }
            'C' -> { val n = csi.toString().toIntOrNull() ?: 1; repeat(n) { if (col >= cur.length) cur.append(' '); col++ } }
            'D' -> { val n = csi.toString().toIntOrNull() ?: 1; col = (col - n).coerceAtLeast(0) }
            'G' -> col = ((csi.toString().toIntOrNull() ?: 1) - 1).coerceAtLeast(0)
            else -> {}
        }
    }

    private fun put(ch: Char) {
        while (cur.length < col) cur.append(' ')
        if (col < cur.length) cur.setCharAt(col, ch) else cur.append(ch)
        col++
    }

    private fun newline() {
        done.addLast(cur.toString()); cur.setLength(0); col = 0
        while (done.size > maxLines) done.removeFirst()
    }

    @Synchronized fun lines(): List<String> = done.toList() + cur.toString()
    @Synchronized fun text(): String = lines().joinToString("\n")
}
