package tech.astrix.droidvm.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.astrix.droidvm.BuildConfig
import tech.astrix.droidvm.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen() {
    val ctx = LocalContext.current
    fun open(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    Scaffold(topBar = { TopAppBar(title = { Text("About") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.height(8.dp))
            Image(painterResource(R.drawable.droidvm_logo), "DroidVM", Modifier.size(128.dp).clip(RoundedCornerShape(36.dp)))
            Text("DroidVM", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Creator: Astrix Tech", style = MaterialTheme.typography.titleMedium)
            Card(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                val c = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ListItem(headlineContent = { Text("Developer Telegram") }, supportingContent = { Text("https://t.me/astrixtech") },
                    leadingContent = { Icon(Icons.AutoMirrored.Outlined.Send, null) }, colors = c,
                    modifier = Modifier.clip(MaterialTheme.shapes.large).clickableItem { open("https://t.me/astrixtech") })
                ListItem(headlineContent = { Text("Official project channel") }, supportingContent = { Text("https://t.me/droidVM_app") },
                    leadingContent = { Icon(Icons.Outlined.Campaign, null) }, colors = c,
                    modifier = Modifier.clip(MaterialTheme.shapes.large).clickableItem { open("https://t.me/droidVM_app") })
            }
            Card(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row0(Icons.Outlined.Gavel, "Open-source components")
                    Text("Virtualization engine: QEMU (GPL-2.0), https://www.qemu.org — binaries built by the Termux project (https://github.com/termux/termux-packages), with their libraries (glib, pixman, libslirp, zstd, …) under their respective licenses. Firmware: SeaBIOS (LGPL-3.0), EDK2/OVMF (BSD-2-Clause-Patent).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Android UI: Jetpack Compose, Material 3 (Apache-2.0).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Row0(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

private fun Modifier.clickableItem(onClick: () -> Unit) = this.clickable(onClick = onClick)
