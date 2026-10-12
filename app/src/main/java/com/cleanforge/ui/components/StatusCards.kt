package com.cleanforge.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cleanforge.core.privileged.CapabilityReport
import com.cleanforge.core.privileged.CapabilityStatus
import com.cleanforge.core.privileged.OperationCapability
import com.cleanforge.core.shizuku.ShizukuManager
import com.cleanforge.core.shizuku.ShizukuState
import com.cleanforge.ui.formatBytes
import java.text.DateFormat
import java.util.Date

fun openAllFilesSettings(context: Context) {
    val uri = Uri.parse("package:${context.packageName}")
    try {
        context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri))
    } catch (e: Exception) {
        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }
}

fun openShizukuApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(ShizukuManager.SHIZUKU_PACKAGE)
    if (intent != null) runCatching { context.startActivity(intent) }
}

@Composable
fun StorageHero(freeBytes: Long, totalBytes: Long, usedFraction: Float, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { usedFraction },
                    modifier = Modifier.size(96.dp),
                    strokeWidth = 9.dp,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
                Text(
                    "${(usedFraction * 100).toInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Column {
                Text(
                    formatBytes(freeBytes),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    "free of ${formatBytes(totalBytes)}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    body: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                icon()
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) {
                Spacer(Modifier.height(12.dp))
                action()
            }
        }
    }
}

@Composable
fun AllFilesCard(onOpenSettings: () -> Unit) {
    InfoCard(
        title = "Allow access to files",
        body = "CleanForge needs All-files access to list and delete things in shared storage. " +
            "It only deletes items you select and confirm, and the app has no internet permission.",
        icon = { Icon(Icons.Default.FolderOpen, null, tint = MaterialTheme.colorScheme.primary) },
        action = { FilledTonalButton(onClick = onOpenSettings) { Text("Open settings") } }
    )
}

@Composable
fun ShizukuCard(
    state: ShizukuState,
    report: CapabilityReport?,
    onRequestPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onRecheck: () -> Unit
) {
    val icon: @Composable () -> Unit = { Icon(Icons.Default.Bolt, null, tint = MaterialTheme.colorScheme.primary) }
    when (state) {
        is ShizukuState.Authorized -> {
            InfoCard(
                title = "Shizuku is running and CleanForge may use it",
                body = (if (state.isRoot)
                    "Running in root mode. CleanForge still only touches the exact folders it explains to you."
                else
                    "Running in shell mode (not root). The shell user can reach Android/data and Android/obb but not other apps' private data.") +
                    " This alone does not prove that every cleanup works on your phone; the checks below do.",
                icon = icon
            )
            if (report != null) CapabilityCard(report, onRecheck)
        }
        ShizukuState.NotInstalled -> InfoCard(
            title = "Shizuku is optional",
            body = "Without it, Android 11+ hides other apps' cache folders (Android/data), so those cleaners are " +
                "switched off. Everything else works. Install and start Shizuku, then come back.",
            icon = icon,
            action = { OutlinedButton(onClick = onRecheck) { Text("Check again") } }
        )
        ShizukuState.NotRunning -> InfoCard(
            title = "Shizuku is not running",
            body = "Start Shizuku (wireless debugging or adb), then return here. It is not root.",
            icon = icon,
            action = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenShizuku) { Text("Open Shizuku") }
                    OutlinedButton(onClick = onRecheck) { Text("Check again") }
                }
            }
        )
        ShizukuState.PermissionDenied -> InfoCard(
            title = "Shizuku permission needed",
            body = "Allow CleanForge in Shizuku to clean app caches and uninstalled-app leftovers.",
            icon = icon,
            action = { Button(onClick = onRequestPermission) { Text("Grant permission") } }
        )
        ShizukuState.PermissionPermanentlyDenied -> InfoCard(
            title = "Shizuku permission was blocked",
            body = "You chose \"don't ask again\", so Android will not show the dialog again. Open the Shizuku app, " +
                "find CleanForge in its list of authorized apps and allow it, then come back.",
            icon = icon,
            action = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onOpenShizuku) { Text("Open Shizuku") }
                    OutlinedButton(onClick = onRecheck) { Text("Check again") }
                }
            }
        )
        ShizukuState.Disconnected -> InfoCard(
            title = "Shizuku disconnected",
            body = "The Shizuku service stopped. Start it again, then return here.",
            icon = icon,
            action = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenShizuku) { Text("Open Shizuku") }
                    OutlinedButton(onClick = onRecheck) { Text("Check again") }
                }
            }
        )
        ShizukuState.ServerTooOld -> InfoCard(
            title = "Shizuku is too old",
            body = "CleanForge needs Shizuku version 11 or newer. Update the Shizuku app, restart it, then come back.",
            icon = icon,
            action = { OutlinedButton(onClick = onOpenShizuku) { Text("Open Shizuku") } }
        )
        is ShizukuState.Error -> InfoCard(
            title = "Shizuku problem",
            body = state.message,
            icon = icon,
            action = { OutlinedButton(onClick = onRecheck) { Text("Check again") } }
        )
    }
}

/** One row per privileged operation: what it needs, whether it works HERE, and its limits. Nothing is assumed. */
@Composable
fun CapabilityCard(report: CapabilityReport, onRecheck: () -> Unit) {
    InfoCard(
        title = "What Shizuku can do here",
        body = report.operations.values.joinToString("\n\n") { c ->
            val checked = c.checkedAtMs?.let { " (" + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) + ")" } ?: ""
            "${c.operation.title}\n${capabilityLabel(c)}$checked: ${c.reason}"
        } + (report.serviceUid?.let { "\n\nService runs as uid $it" + (if (it == 0) " (root)." else " (shell, not root).") } ?: ""),
        icon = { Icon(Icons.Default.Bolt, null, tint = MaterialTheme.colorScheme.primary) },
        action = { OutlinedButton(onClick = onRecheck) { Text("Check again") } }
    )
}

private fun capabilityLabel(c: OperationCapability): String = when (c.status) {
    CapabilityStatus.VERIFIED -> "Verified"
    CapabilityStatus.PREREQUISITES_MET -> "Ready, not proven yet"
    CapabilityStatus.NEEDS_AUTHORIZATION -> "Needs permission"
    CapabilityStatus.UNAVAILABLE -> "Unavailable right now"
    CapabilityStatus.UNSUPPORTED -> "Not supported on this device"
    CapabilityStatus.UNKNOWN -> "Not checked"
}

@Composable
fun ScanBar(
    scanning: Boolean,
    label: String,
    buttonText: String,
    hint: String,
    onScan: () -> Unit,
    onCancel: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (scanning) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Stop scanning") }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        } else {
            Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Search, null)
                Spacer(Modifier.size(8.dp))
                Text(buttonText)
            }
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun NoticeCard(notices: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Skipped or incomplete", style = MaterialTheme.typography.titleSmall)
            notices.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
