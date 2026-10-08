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
import com.cleanforge.core.shizuku.ShizukuManager
import com.cleanforge.core.shizuku.ShizukuState
import com.cleanforge.ui.formatBytes

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
fun ShizukuCard(state: ShizukuState, onRequestPermission: () -> Unit, onOpenShizuku: () -> Unit) {
    val icon: @Composable () -> Unit = { Icon(Icons.Default.Bolt, null, tint = MaterialTheme.colorScheme.primary) }
    when (state) {
        is ShizukuState.Authorized -> InfoCard(
            title = "Shizuku connected",
            body = if (state.isRoot)
                "Running in root mode. CleanForge still only touches the exact folders it explains to you."
            else
                "Running in shell mode (not root). App caches and uninstalled-app leftovers are available.",
            icon = icon
        )
        ShizukuState.NotInstalled -> InfoCard(
            title = "Shizuku is optional",
            body = "Without it, Android 11+ hides other apps' cache folders (Android/data), so those cleaners are " +
                "switched off. Everything else works. Install and start Shizuku, then come back.",
            icon = icon
        )
        ShizukuState.NotRunning -> InfoCard(
            title = "Shizuku is not running",
            body = "Start Shizuku (wireless debugging or adb), then return here. It is not root.",
            icon = icon,
            action = { OutlinedButton(onClick = onOpenShizuku) { Text("Open Shizuku") } }
        )
        ShizukuState.PermissionDenied -> InfoCard(
            title = "Shizuku permission needed",
            body = "Allow CleanForge in Shizuku to clean app caches and uninstalled-app leftovers.",
            icon = icon,
            action = { Button(onClick = onRequestPermission) { Text("Grant permission") } }
        )
        ShizukuState.Disconnected -> InfoCard(
            title = "Shizuku disconnected",
            body = "The Shizuku service stopped. Start it again, then return here.",
            icon = icon,
            action = { OutlinedButton(onClick = onOpenShizuku) { Text("Open Shizuku") } }
        )
        is ShizukuState.Error -> InfoCard(title = "Shizuku problem", body = state.message, icon = icon)
    }
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
