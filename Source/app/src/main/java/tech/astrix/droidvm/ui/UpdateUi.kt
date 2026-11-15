package tech.astrix.droidvm.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.astrix.droidvm.BuildConfig
import tech.astrix.droidvm.i18n.tr
import tech.astrix.droidvm.update.ReleaseInfo
import tech.astrix.droidvm.update.UpdateChecker
import tech.astrix.droidvm.update.UpdateResult

/** Release name, tag, description and an Install button (shared by the Settings card and the launch dialog). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReleaseDetails(r: ReleaseInfo, maxBodyHeight: Int = 260) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableFloatStateOf(0f) }
    var downloading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(r.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(tr("Tag: {}").replace("{}", r.tag) + (if (r.publishedAt.isNotBlank()) " • ${r.publishedAt}" else "") +
            (if (r.prerelease) " • " + tr("pre-release") else ""), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary)
        Text(tr("Installed: {}").replace("{}", BuildConfig.VERSION_NAME) + " • " +
            (if (r.isNewer) tr("update available") else tr("you have the latest version")), style = MaterialTheme.typography.bodySmall)
        if (r.body.isNotBlank()) Text(r.body, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.heightIn(max = maxBodyHeight.dp).verticalScroll(rememberScrollState()))
        else Text(tr("No description."), style = MaterialTheme.typography.bodySmall)
        if (downloading) {
            LinearProgressIndicator(progress = { if (progress < 0) 0f else progress }, modifier = Modifier.fillMaxWidth())
            Text(if (progress >= 0) tr("Downloading… {}%").replace("{}", "${(progress * 100).toInt()}") else tr("Downloading…"),
                style = MaterialTheme.typography.bodySmall)
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = r.apk != null && !downloading, onClick = {
                downloading = true; status = null; progress = 0f
                scope.launch {
                    runCatching { UpdateChecker.download(ctx, r) { progress = it } }
                        .onSuccess { f ->
                            if (!UpdateChecker.install(ctx, f))
                                Toast.makeText(ctx, tr("Allow installing apps from DroidVM, then press Install again"), Toast.LENGTH_LONG).show()
                        }
                        .onFailure { status = tr("Download failed: {}").replace("{}", it.message ?: "") }
                    downloading = false
                }
            }) { Text(if (r.apk == null) tr("No APK in release") else tr("Install") + r.apk.size.takeIf { it > 0 }?.let { " (${it / 1048576} MB)" }.orEmpty()) }
            OutlinedButton(onClick = { if (!UpdateChecker.openPage(ctx, r.htmlUrl)) Toast.makeText(ctx, tr("No browser found"), Toast.LENGTH_LONG).show() }) {
                Text(tr("Open on GitHub"))
            }
        }
        Hint("The APK is downloaded from the GitHub release, checked (package name and signing key) and handed to Android's package installer, which asks you to confirm.")
    }
}

@Composable
fun UpdatesCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateResult?>(null) }
    SectionCard("Updates", Icons.Outlined.SystemUpdate) {
        Hint("Releases are taken from GitHub: {}".replace("{}", UpdateChecker.REPO_URL))
        FilledTonalButton(enabled = !checking, onClick = {
            checking = true
            scope.launch { result = UpdateChecker.check(); checking = false }
        }) { Text(if (checking) tr("Checking…") else tr("Check for updates")) }
        if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
        when (val r = result) {
            is UpdateResult.Found -> ReleaseDetails(r.release)
            UpdateResult.NoReleases -> {
                Text(tr("No releases have been published in the repository yet."), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { UpdateChecker.openPage(ctx, UpdateChecker.REPO_URL) }) { Text(tr("Open on GitHub")) }
            }
            is UpdateResult.Failed -> Text(tr("Update check failed: {}").replace("{}", r.message), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            null -> {}
        }
    }
}

@Composable
fun UpdateDialog(r: ReleaseInfo, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { androidx.compose.material3.Icon(Icons.Outlined.SystemUpdate, null) },
        title = { Text(tr("Update available")) },
        text = { ReleaseDetails(r, maxBodyHeight = 220) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Later")) } },
    )
}
