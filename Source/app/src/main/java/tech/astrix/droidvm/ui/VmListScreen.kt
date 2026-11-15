package tech.astrix.droidvm.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tech.astrix.droidvm.DroidVmApp
import tech.astrix.droidvm.EngineState
import tech.astrix.droidvm.R
import tech.astrix.droidvm.data.VmConfig
import tech.astrix.droidvm.display.DisplayActivity
import tech.astrix.droidvm.engine.VmManager
import tech.astrix.droidvm.engine.VmRuntime
import tech.astrix.droidvm.engine.VmState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VmListScreen(onOpen: (String) -> Unit, onCreate: () -> Unit) {
    val vms by DroidVmApp.repo.vms.collectAsStateWithLifecycle()
    val states by VmManager.states.collectAsStateWithLifecycle()
    val engine by DroidVmApp.engine.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("DroidVM") },
                navigationIcon = {
                    Image(painterResource(R.drawable.droidvm_logo), null,
                        Modifier.padding(start = 12.dp, end = 4.dp).size(36.dp).clip(RoundedCornerShape(10.dp)))
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onCreate, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New VM") })
        },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(340.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { EngineBanner(engine) }
            if (vms.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyState() }
            items(vms, key = { it.id }) { vm ->
                VmCard(vm, states[vm.id] ?: VmRuntime(VmState.STOPPED), onOpen = { onOpen(vm.id) })
            }
        }
    }
}

@Composable
private fun EngineBanner(engine: EngineState) {
    AnimatedVisibility(engine !is EngineState.Ready) {
        Card(colors = CardDefaults.cardColors(
            containerColor = if (engine is EngineState.Failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer),
            shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp).fillMaxWidth()) {
                when (engine) {
                    is EngineState.Preparing -> {
                        Text("Preparing QEMU engine…", style = MaterialTheme.typography.titleMedium)
                        Text(engine.step, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    is EngineState.Failed -> {
                        Text("Engine unavailable", style = MaterialTheme.typography.titleMedium)
                        Text(engine.message, style = MaterialTheme.typography.bodyMedium)
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.droidvm_logo), null, Modifier.size(120.dp).clip(RoundedCornerShape(32.dp)))
        Spacer(Modifier.height(20.dp))
        Text("No virtual machines yet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Import a Linux ISO, IMG or QCOW2 image in the Images tab, then create a VM.",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 32.dp))
    }
}

@Composable
private fun VmCard(vm: VmConfig, rt: VmRuntime, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val running = rt.state == VmState.RUNNING || rt.state == VmState.PAUSED || rt.state == VmState.STARTING
    Card(
        onClick = onOpen,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.animateContentSize(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Icon(Icons.Outlined.Memory, null, Modifier.padding(12.dp).size(28.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(vm.name, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    Text("${vm.arch.label} • ${vm.cpus} vCPU • ${vm.ramMb / 1024.0} GB".replace(".0 GB", " GB"),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StateChip(rt.state)
            }
            if (rt.state == VmState.ERROR && rt.message != null) {
                Spacer(Modifier.height(8.dp))
                Text(rt.message.lineSequence().take(3).joinToString("\n"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, maxLines = 3)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${vm.width}×${vm.height}" + (vm.cdrom?.let { " • ISO" } ?: "") + " • ${vm.disks.size} disk(s)",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                if (running) {
                    FilledTonalIconButton(onClick = { ctx.startActivity(DisplayActivity.intent(ctx, vm.id)) }) {
                        Icon(Icons.Outlined.DesktopWindows, "Display")
                    }
                    FilledTonalIconButton(onClick = { scope.launch { VmManager.powerDown(vm.id) } }) {
                        Icon(Icons.Filled.Stop, "Shut down")
                    }
                } else {
                    FilledTonalIconButton(onClick = {
                        VmManager.start(vm)
                        ctx.startActivity(DisplayActivity.afterStart(ctx, vm))
                    }) { Icon(Icons.Filled.PlayArrow, "Start") }
                }
            }
        }
    }
}
