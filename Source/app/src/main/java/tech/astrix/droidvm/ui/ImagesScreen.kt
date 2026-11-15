package tech.astrix.droidvm.ui

import tech.astrix.droidvm.i18n.tr
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.astrix.droidvm.DroidVmApp
import tech.astrix.droidvm.data.ImageEntry
import tech.astrix.droidvm.data.ImageInspector
import tech.astrix.droidvm.data.ImportManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagesScreen() {
    val ctx = LocalContext.current
    val repo = DroidVmApp.repo
    val images by repo.images.collectAsStateWithLifecycle()
    val vms by repo.vms.collectAsStateWithLifecycle()
    val progress by ImportManager.progress.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<ImageEntry?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) ImportManager.import(ctx.applicationContext, uri, repo)
    }
    val busy = progress?.done == false

    Scaffold(
        topBar = { TopAppBar(title = { Text("Images") }) },
        floatingActionButton = {
            if (!busy) ExtendedFloatingActionButton(onClick = { picker.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Import image") })
        },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize(),
        ) {
            item {
                AnimatedVisibility(progress != null) {
                    val p = progress ?: return@AnimatedVisibility
                    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(
                        containerColor = if (p.error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(when {
                                p.error != null -> "Import failed: ${p.name}"
                                p.done -> "Imported: ${p.name}"
                                else -> "Importing ${p.name}"
                            }, style = MaterialTheme.typography.titleMedium)
                            when {
                                p.error != null -> Text(p.error, style = MaterialTheme.typography.bodyMedium)
                                p.done -> Text(p.result?.let { "${it.format.label} • ${ImageInspector.human(it.sizeBytes)}" + (it.detectedArch?.let { a -> " • ${a.label}" } ?: "") } ?: "",
                                    style = MaterialTheme.typography.bodyMedium)
                                else -> {
                                    if (p.total > 0) LinearProgressIndicator(progress = { (p.copied.toFloat() / p.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                                    Text("${ImageInspector.human(p.copied)}" + if (p.total > 0) " / ${ImageInspector.human(p.total)}" else "", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                if (p.done) TextButton(onClick = { ImportManager.dismiss() }) { Text("OK") }
                                else TextButton(onClick = { ImportManager.cancel() }) { Text("Cancel") }
                            }
                        }
                    }
                }
            }
            item {
                Hint("Supported: ISO (installation / live CD), IMG / RAW disk images, QCOW2. The file is copied into the app's private storage and checked by its content, not only by extension. Compressed files (.xz, .gz, .zip) must be unpacked first.")
            }
            if (images.isEmpty() && progress == null) item {
                Text("No images imported yet.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 24.dp))
            }
            items(images, key = { it.id }) { e ->
                val usedBy = vms.filter { vm -> vm.cdrom == e.path || vm.disks.any { it.path == e.path } }
                Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Icon(if (e.format.isOptical) Icons.Outlined.Album else Icons.Outlined.Storage, null,
                                Modifier.padding(10.dp).size(26.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                            Text("${e.format.label} • ${ImageInspector.human(e.sizeBytes)}" + (e.detectedArch?.let { " • ${it.label}" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (e.details.isNotBlank()) Text(e.details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                            if (usedBy.isNotEmpty()) Text("Used by: " + usedBy.joinToString { it.name }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { toDelete = e }) { Icon(Icons.Outlined.Delete, "Delete") }
                    }
                }
            }
        }
    }
    toDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete ${e.name}?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("The copy in DroidVM storage will be removed. VMs that use it will lose this disk / CD-ROM."))
                Text(tr("File path:"), style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(e.path, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                val users = vms.filter { vm -> vm.cdrom == e.path || vm.disks.any { it.path == e.path } }
                if (users.isNotEmpty()) Text(tr("Used by: ") + users.joinToString { it.name }, style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(onClick = {
                toDelete = null
                repo.deleteImage(e.id)
                vms.forEach { vm ->
                    if (vm.cdrom == e.path || vm.disks.any { it.path == e.path })
                        repo.saveVm(vm.copy(cdrom = vm.cdrom.takeIf { it != e.path }, disks = vm.disks.filterNot { it.path == e.path }))
                }
            }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}
