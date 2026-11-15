package tech.astrix.droidvm.ui

import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.outlined.Bolt
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.astrix.droidvm.DroidVmApp
import tech.astrix.droidvm.data.ImageInspector
import tech.astrix.droidvm.engine.HostCaps
import tech.astrix.droidvm.engine.NativeEnv

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val repo = DroidVmApp.repo
    val s by repo.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var root by remember { mutableStateOf<Boolean?>(null) }
    var rootMsg by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableStateOf("…") }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    LaunchedEffect(Unit) { version = withContext(Dispatchers.IO) { NativeEnv.qemuVersion() } }
    val allFiles = remember(tick) { Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager() }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            UpdatesCard()

            SectionCard("Appearance", Icons.Outlined.Palette) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("System", "Light", "Dark").forEachIndexed { i, l ->
                        SegmentedButton(selected = s.theme == i, onClick = { repo.saveSettings(s.copy(theme = i)) },
                            shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
                    }
                }
                if (Build.VERSION.SDK_INT >= 31)
                    SwitchRow("Dynamic color", "Use wallpaper colors instead of the DroidVM green palette", s.dynamicColor) {
                        repo.saveSettings(s.copy(dynamicColor = it))
                    }
            }
            SectionCard("Display & input", Icons.Outlined.TouchApp) {
                SwitchRow("Keep screen on", "While a VM display is open", s.keepScreenOn) { repo.saveSettings(s.copy(keepScreenOn = it)) }
                Text("Default touch mode", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("Touchpad (cursor)", "Direct touch").forEachIndexed { i, l ->
                        SegmentedButton(selected = s.defaultTouchMode == i, onClick = { repo.saveSettings(s.copy(defaultTouchMode = i)) },
                            shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(l) }
                    }
                }
            }
            SectionCard("Background work", Icons.Outlined.Bolt) {
                Hint("While a VM runs, DroidVM shows an ongoing notification (like Termux) with Stop and Wake lock buttons. Nothing runs in the background when no VM is started.")
                SwitchRow("Acquire wake lock by default", "Keeps the CPU awake with the screen off while VMs run. Can be toggled from the notification.", s.wakeLock) {
                    repo.saveSettings(s.copy(wakeLock = it))
                }
                val pm = ctx.getSystemService(android.os.PowerManager::class.java)
                val unrestricted = remember(tick) { Build.VERSION.SDK_INT < 23 || pm.isIgnoringBatteryOptimizations(ctx.packageName) }
                InfoLine("Battery optimization", if (unrestricted) "Not optimized" else "Optimized (Android may pause VMs)")
                if (Build.VERSION.SDK_INT >= 23) OutlinedButton(onClick = {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                        .onFailure { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }
                }) { Text("Open battery optimization settings") }
                Hint("Optional. In the system list choose DroidVM → \"Don't optimize\" if long-running VMs get stopped with the screen off.")
            }
            SectionCard("Storage", Icons.Outlined.Folder) {
                InfoLine("App storage free", ImageInspector.human(repo.root.usableSpace))
                InfoLine("All files access", if (allFiles) "Granted" else "Not granted")
                Hint("Needed only for shared folders outside the app directory (e.g. /sdcard/DroidVM/Shared).")
                if (!allFiles && Build.VERSION.SDK_INT >= 30) FilledTonalButton(onClick = {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                    }.onFailure { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                }) { Text("Grant all files access") }
            }
            SectionCard("Virtualization", Icons.Outlined.DeveloperBoard) {
                InfoLine("Device CPU", "${Build.SUPPORTED_ABIS.firstOrNull()} • ${HostCaps.cpuCount()} cores")
                InfoLine("Device RAM", "${HostCaps.totalRamMb()} MB")
                InfoLine("QEMU", version)
                val kvm = remember(tick, rootMsg) {
                    when {
                        !HostCaps.kvmNodePresent() -> "Not available (no /dev/kvm in this kernel)"
                        HostCaps.kvmAccessible() -> "Available"
                        else -> "Present, but not accessible to apps"
                    }
                }
                InfoLine("KVM", kvm)
                Hint("Without KVM, DroidVM uses QEMU's TCG software emulation. It works on any device but is much slower than real hardware; lightweight desktops (XFCE, LXQt, i3) are recommended.")
            }
            SectionCard("Root (optional)", Icons.Outlined.Security) {
                InfoLine("Root", when (root) { null -> "Not checked"; true -> "Available"; false -> "Not available" })
                FilledTonalButton(onClick = { scope.launch { root = withContext(Dispatchers.IO) { HostCaps.rootAvailable(refresh = true) } } }) { Text("Check root") }
                if (root == true && HostCaps.kvmNodePresent() && !HostCaps.kvmAccessible())
                    FilledTonalButton(onClick = {
                        scope.launch {
                            val (ok, msg) = withContext(Dispatchers.IO) { HostCaps.enableKvmWithRoot() }
                            rootMsg = (if (ok) "KVM enabled until reboot. " else "Failed: ") + msg; tick++
                        }
                    }) { Text("Allow KVM access with root") }
                rootMsg?.let { Hint(it) }
                Hint("Root is never required. It is only used, on request, to make an existing /dev/kvm accessible.")
            }
        }
    }
}
