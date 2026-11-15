package tech.astrix.droidvm.display

import tech.astrix.droidvm.i18n.tr
import tech.astrix.droidvm.data.LayoutSwitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.material3.AssistChip
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.PanTool
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardAlt
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import tech.astrix.droidvm.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.astrix.droidvm.DroidVmApp
import tech.astrix.droidvm.engine.VmManager
import tech.astrix.droidvm.engine.VmRuntime
import tech.astrix.droidvm.engine.VmState
import tech.astrix.droidvm.ui.theme.DroidVmTheme
import tech.astrix.droidvm.vnc.KeyMap

class DisplayActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_ID = "vmId"
        fun intent(ctx: Context, id: String) = Intent(ctx, DisplayActivity::class.java)
            .putExtra(EXTRA_ID, id)
        /** Screen to show right after starting a VM: the console when "open console on start" is set. */
        fun afterStart(ctx: Context, vm: tech.astrix.droidvm.data.VmConfig): Intent =
            if (vm.openConsoleOnStart) ConsoleActivity.intent(ctx, vm.id) else intent(ctx, vm.id)
    }

    private lateinit var vmId: String
    private lateinit var session: VncSession
    private lateinit var screen: VmScreenView
    private val connected = mutableStateOf(false)
    private val lastError = mutableStateOf<String?>(null)
    private val resolution = mutableStateOf(0 to 0)
    private val stickyTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        vmId = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        val vm = DroidVmApp.repo.vm(vmId) ?: run { finish(); return }
        val settings = DroidVmApp.repo.settings.value
        if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC

        session = VncSession(VmManager.paths(vmId).vnc.absolutePath, object : VncSession.Callbacks {
            override fun onFrame() = screen.requestFrame()
            override fun onResized(w: Int, h: Int) { resolution.value = w to h; screen.requestFrame(); screen.requestLayout() }
            override fun onCursorChanged() = screen.requestFrame()
            override fun onState(connected: Boolean, error: String?) {
                this@DisplayActivity.connected.value = connected
                if (!connected) lastError.value = error
            }
            override fun onClipboard(text: String) {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("VM", text))
            }
        })
        session.audio.volume = settings.audioVolume
        screen = VmScreenView(this, session).apply {
            touchMode = if (settings.defaultTouchMode == 1) TouchMode.DIRECT else TouchMode.TOUCHPAD
            onStickyChanged = { stickyTick.intValue++ }
        }

        setContent {
            DroidVmTheme(2, false) { DisplayUi(vm.name) }
        }
    }

    @Composable
    private fun DisplayUi(name: String) {
        val states by VmManager.states.collectAsStateWithLifecycle()
        val rt = states[vmId] ?: VmRuntime(VmState.STOPPED)
        val isConnected by connected
        val scope = rememberCoroutineScope()
        var toolbar by remember { mutableStateOf(true) }
        var keysBar by remember { mutableStateOf(false) }
        var fullscreen by remember { mutableStateOf(false) }
        var touchMode by remember { mutableStateOf(screen.touchMode) }
        var scaleMode by remember { mutableStateOf(screen.scaleMode) }
        var powerMenu by remember { mutableStateOf(false) }
        var volumePanel by remember { mutableStateOf(false) }
        var volume by remember { mutableFloatStateOf(session.audio.volume) }
        var muted by remember { mutableStateOf(false) }
        val res by resolution

        // Connect / reconnect loop: the VNC socket appears a moment after QEMU starts.
        LaunchedEffect(rt.state, isConnected) {
            if (isConnected) return@LaunchedEffect
            while (!isConnected && rt.state in setOf(VmState.STARTING, VmState.RUNNING, VmState.PAUSED)) {
                if (VmManager.paths(vmId).vnc.exists()) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { session.connect() }
                    delay(1500)
                } else delay(500)
            }
        }
        LaunchedEffect(fullscreen) {
            val c = WindowCompat.getInsetsController(window, window.decorView)
            if (fullscreen) {
                c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsetsCompat.Type.systemBars())
            } else c.show(WindowInsetsCompat.Type.systemBars())
        }
        DisposableEffect(Unit) { onDispose { } }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { screen }, modifier = Modifier.fillMaxSize().then(if (fullscreen) Modifier else Modifier.safeDrawingPadding()).imePadding())

            if (!isConnected) {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (rt.state) {
                        VmState.STARTING, VmState.RUNNING, VmState.PAUSED -> {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text(if (rt.state == VmState.STARTING) "Starting $name…" else "Connecting to display…", color = Color.White)
                            TextButton(onClick = { startActivity(ConsoleActivity.intent(this@DisplayActivity, vmId)) }) { Text("Open console") }
                        }
                        VmState.ERROR -> {
                            Text("VM failed", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
                            Text(rt.message ?: "", color = Color.White, style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { startActivity(ConsoleActivity.intent(this@DisplayActivity, vmId)) }) { Text("Open console") }
                                TextButton(onClick = { finish() }) { Text("Close") }
                            }
                        }
                        else -> {
                            Text("$name is stopped", style = MaterialTheme.typography.titleLarge, color = Color.White)
                            Spacer(Modifier.height(8.dp))
                            Row {
                                TextButton(onClick = { DroidVmApp.repo.vm(vmId)?.let { VmManager.start(it) } }) { Text("Start") }
                                TextButton(onClick = { finish() }) { Text("Close") }
                            }
                        }
                    }
                }
            }
            if (rt.state == VmState.PAUSED && isConnected)
                Surface(Modifier.align(Alignment.Center), shape = RoundedCornerShape(20.dp), color = Color(0xCC000000)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Paused", color = Color.White)
                        TextButton(onClick = { scope.launch { VmManager.resume(vmId) } }) { Text("Resume") }
                    }
                }

            Column(Modifier.align(Alignment.TopCenter).then(if (fullscreen) Modifier.padding(top = 4.dp) else Modifier.safeDrawingPadding()).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedVisibility(toolbar, enter = slideInVertically() + fadeIn(), exit = slideOutVertically() + fadeOut()) {
                    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                        modifier = Modifier.padding(8.dp)) {
                        Row(Modifier.padding(horizontal = 6.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { screen.showKeyboard() }) { Icon(Icons.Filled.Keyboard, "Keyboard") }
                            IconButton(onClick = { startActivity(ConsoleActivity.intent(this@DisplayActivity, vmId)) }) { Icon(Icons.Filled.Terminal, "Console") }
                            LayoutButton()
                            IconButton(onClick = { keysBar = !keysBar }) { Icon(Icons.Filled.KeyboardAlt, "Special keys", tint = if (keysBar) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                            IconButton(onClick = {
                                touchMode = if (touchMode == TouchMode.TOUCHPAD) TouchMode.DIRECT else TouchMode.TOUCHPAD
                                screen.touchMode = touchMode
                            }) { Icon(if (touchMode == TouchMode.TOUCHPAD) Icons.Filled.Mouse else Icons.Filled.TouchApp, touchMode.label) }
                            TextButton(onClick = {
                                scaleMode = ScaleMode.entries[(scaleMode.ordinal + 1) % ScaleMode.entries.size]
                                screen.scaleMode = scaleMode
                            }) { Icon(Icons.Filled.ZoomOutMap, null); Text(" ${scaleMode.label}") }
                            IconButton(onClick = { fullscreen = !fullscreen }) { Icon(if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, "Fullscreen") }
                            IconButton(onClick = { volumePanel = !volumePanel }) { Icon(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, "Volume") }
                            IconButton(onClick = {
                                val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                                clip?.getItemAt(0)?.coerceToText(this@DisplayActivity)?.let { session.typeText(it) }
                            }) { Icon(Icons.Filled.ContentPaste, "Type clipboard") }
                            Box {
                                IconButton(onClick = { powerMenu = true }) { Icon(Icons.Filled.PowerSettingsNew, "Power") }
                                DropdownMenu(powerMenu, { powerMenu = false }) {
                                    val paused = rt.state == VmState.PAUSED
                                    listOf(if (paused) "Resume" else "Pause", "Shut down (ACPI)", "Reboot", "Ctrl+Alt+Del", "Save state & quit", "Force stop").forEachIndexed { i, label ->
                                        DropdownMenuItem(text = { Text(label) }, onClick = {
                                            powerMenu = false
                                            scope.launch {
                                                when (i) {
                                                    0 -> if (paused) VmManager.resume(vmId) else VmManager.pause(vmId)
                                                    1 -> VmManager.powerDown(vmId)
                                                    2 -> VmManager.reset(vmId)
                                                    3 -> session.sendCombo(K(KeyEvent.KEYCODE_CTRL_LEFT), K(KeyEvent.KEYCODE_ALT_LEFT), K(KeyEvent.KEYCODE_FORWARD_DEL))
                                                    4 -> VmManager.saveStateAndQuit(vmId).onSuccess { finish() }
                                                    else -> VmManager.forceStop(vmId)
                                                }
                                            }
                                        })
                                    }
                                }
                            }
                            if (res.first > 0) Text("${res.first}×${res.second}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp))
                            IconButton(onClick = { toolbar = false }) { Icon(Icons.Filled.Menu, "Hide toolbar") }
                            IconButton(onClick = { finish() }) { Icon(Icons.Filled.Close, "Close display") }
                        }
                    }
                }
                AnimatedVisibility(volumePanel && toolbar) {
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)) {
                        Row(Modifier.padding(horizontal = 12.dp).width(300.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { muted = !muted; session.audio.volume = if (muted) 0f else volume }) {
                                Icon(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, "Mute")
                            }
                            Slider(volume, {
                                volume = it; muted = false; session.audio.volume = it
                            }, onValueChangeFinished = {
                                DroidVmApp.repo.saveSettings(DroidVmApp.repo.settings.value.copy(audioVolume = volume))
                            }, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            if (!toolbar) SmallFloatingActionButton(onClick = { toolbar = true },
                modifier = Modifier.align(Alignment.TopEnd).then(if (fullscreen) Modifier.padding(8.dp) else Modifier.safeDrawingPadding().padding(8.dp)),
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)) { Icon(Icons.Filled.Menu, "Show toolbar") }

            AnimatedVisibility(keysBar, modifier = Modifier.align(Alignment.BottomCenter).imePadding().then(if (fullscreen) Modifier else Modifier.safeDrawingPadding())) {
                ExtraKeys()
            }
        }
    }

    /** Keys of the guest's layout-switch shortcut. */
    private fun layoutKeys(m: LayoutSwitch): Array<KeyMap.K> = when (m) {
        LayoutSwitch.ALT_SHIFT -> arrayOf(K(KeyEvent.KEYCODE_ALT_LEFT), K(KeyEvent.KEYCODE_SHIFT_LEFT))
        LayoutSwitch.CTRL_SHIFT -> arrayOf(K(KeyEvent.KEYCODE_CTRL_LEFT), K(KeyEvent.KEYCODE_SHIFT_LEFT))
        LayoutSwitch.SUPER_SPACE -> arrayOf(K(KeyEvent.KEYCODE_META_LEFT), K(KeyEvent.KEYCODE_SPACE))
        LayoutSwitch.CTRL_SPACE -> arrayOf(K(KeyEvent.KEYCODE_CTRL_LEFT), K(KeyEvent.KEYCODE_SPACE))
        LayoutSwitch.ALT_SPACE -> arrayOf(K(KeyEvent.KEYCODE_ALT_LEFT), K(KeyEvent.KEYCODE_SPACE))
        LayoutSwitch.CAPS_LOCK -> arrayOf(K(KeyEvent.KEYCODE_CAPS_LOCK))
        LayoutSwitch.SHIFT_CAPS -> arrayOf(K(KeyEvent.KEYCODE_SHIFT_LEFT), K(KeyEvent.KEYCODE_CAPS_LOCK))
    }

    /** Toolbar button: tap = switch the guest keyboard layout, arrow = choose the shortcut / Android keyboard. */
    @Composable
    private fun LayoutButton() {
        val settings by DroidVmApp.repo.settings.collectAsStateWithLifecycle()
        var menu by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                session.sendCombo(*layoutKeys(settings.layoutSwitch))
                android.widget.Toast.makeText(this@DisplayActivity,
                    tr("Layout switched ({})").replace("{}", settings.layoutSwitch.label), android.widget.Toast.LENGTH_SHORT).show()
            }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Filled.Language, null); Text(" " + tr("Layout"))
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.ArrowDropDown, "Layout switch shortcut") }
                DropdownMenu(menu, { menu = false }) {
                    Text(tr("Guest layout shortcut"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    LayoutSwitch.entries.forEach { m ->
                        DropdownMenuItem(text = { Text(m.label) }, onClick = {
                            menu = false
                            DroidVmApp.repo.saveSettings(DroidVmApp.repo.settings.value.copy(layoutSwitch = m))
                            session.sendCombo(*layoutKeys(m))
                        }, leadingIcon = { if (m == settings.layoutSwitch) Icon(Icons.Filled.Check, null) })
                    }
                    androidx.compose.material3.HorizontalDivider()
                    DropdownMenuItem(text = { Text("Android keyboard…") }, onClick = {
                        menu = false
                        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showInputMethodPicker()
                    }, leadingIcon = { Icon(Icons.Filled.Keyboard, null) })
                }
            }
        }
    }

    private fun K(code: Int) = KeyMap.forKeyCode(code)!!

    @Composable
    private fun ExtraKeys() {
        @Suppress("UNUSED_VARIABLE") val t = stickyTick.intValue
        val mods = listOf("Ctrl" to KeyEvent.KEYCODE_CTRL_LEFT, "Alt" to KeyEvent.KEYCODE_ALT_LEFT, "Shift" to KeyEvent.KEYCODE_SHIFT_LEFT, "Super" to KeyEvent.KEYCODE_META_LEFT)
        val keys = listOf("Esc" to KeyEvent.KEYCODE_ESCAPE, "Tab" to KeyEvent.KEYCODE_TAB, "←" to KeyEvent.KEYCODE_DPAD_LEFT, "↑" to KeyEvent.KEYCODE_DPAD_UP,
            "↓" to KeyEvent.KEYCODE_DPAD_DOWN, "→" to KeyEvent.KEYCODE_DPAD_RIGHT, "Home" to KeyEvent.KEYCODE_MOVE_HOME, "End" to KeyEvent.KEYCODE_MOVE_END,
            "PgUp" to KeyEvent.KEYCODE_PAGE_UP, "PgDn" to KeyEvent.KEYCODE_PAGE_DOWN, "Ins" to KeyEvent.KEYCODE_INSERT, "Del" to KeyEvent.KEYCODE_FORWARD_DEL,
            "Enter" to KeyEvent.KEYCODE_ENTER, "⌫" to KeyEvent.KEYCODE_DEL) +
            (1..12).map { "F$it" to (if (it <= 10) KeyEvent.KEYCODE_F1 + it - 1 else if (it == 11) KeyEvent.KEYCODE_F11 else KeyEvent.KEYCODE_F12) }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                mods.forEach { (label, code) ->
                    val k = K(code)
                    FilterChip(selected = k in screen.stickyModifiers, onClick = {
                        if (!screen.stickyModifiers.remove(k)) screen.stickyModifiers.add(k)
                        stickyTick.intValue++
                    }, label = { Text(label) })
                }
                FilterChip(selected = holdMode.value, onClick = { holdMode.value = !holdMode.value },
                    label = { Text("Hold") }, leadingIcon = { Icon(Icons.Filled.PanTool, null, Modifier.size(16.dp)) })
                FilterChip(selected = lmbHeld.value, onClick = {
                    lmbHeld.value = !lmbHeld.value
                    if (lmbHeld.value) session.buttonDown(1) else session.buttonUp(1)
                }, label = { Text("Hold left click") })
                if (held.isNotEmpty() || lmbHeld.value)
                    AssistChip(onClick = { releaseAll() }, label = { Text("Release all") })
                keys.forEach { (label, code) ->
                    val k = K(code)
                    HoldKey(label, k in held,
                        onDown = {
                            if (holdMode.value) {
                                if (held.remove(k)) session.key(k, false) else { held.add(k); session.key(k, true) }
                            } else {
                                screen.stickyModifiers.forEach { session.key(it, true) }
                                session.key(k, true)
                            }
                        },
                        onUp = {
                            if (!holdMode.value) {
                                session.key(k, false)
                                if (screen.stickyModifiers.isNotEmpty()) {
                                    screen.stickyModifiers.reversed().forEach { session.key(it, false) }
                                    screen.stickyModifiers.clear(); stickyTick.intValue++
                                }
                            }
                        })
                }
            }
        }
    }

    /** Keys stay pressed in the guest while the finger is on them (the guest auto-repeats);
     *  in "Hold" mode a tap latches the key down until it is tapped again or "Release all". */
    @Composable
    private fun HoldKey(label: String, latched: Boolean, onDown: () -> Unit, onUp: () -> Unit) {
        var pressed by remember { mutableStateOf(false) }
        val on = pressed || latched
        Surface(shape = RoundedCornerShape(10.dp),
            color = if (on) MaterialTheme.colorScheme.primary else Color.Transparent,
            contentColor = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(width = 56.dp, height = 40.dp).pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true; onDown()
                    waitForUpOrCancellation()
                    pressed = false; onUp()
                }
            }) { Box(contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.labelLarge) } }
    }

    private val held = androidx.compose.runtime.mutableStateListOf<KeyMap.K>()
    private val holdMode = mutableStateOf(false)
    private val lmbHeld = mutableStateOf(false)

    private fun releaseAll() {
        if (!::session.isInitialized) return
        held.reversed().forEach { session.key(it, false) }; held.clear()
        if (lmbHeld.value) { session.buttonUp(1); lmbHeld.value = false }
    }

    override fun onPause() {
        releaseAll()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::screen.isInitialized) screen.requestFocus()
    }

    override fun onDestroy() {
        if (::session.isInitialized) session.close()
        super.onDestroy()
    }
}
