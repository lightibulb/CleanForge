package com.cleanforge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cleanforge.BuildConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text("About") }, scrollBehavior = scroll) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.CleaningServices, null, tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                    Column {
                        Text(
                            "CleanForge",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            "Made by Aman",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            "Version ${BuildConfig.VERSION_NAME} · © 2026 Aman · free and open source",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Fact(
                Icons.Default.WifiOff, "No internet, no tracking",
                "CleanForge does not declare the internet permission. No ads, no analytics, no accounts, " +
                    "and file names never leave your phone."
            )
            Fact(
                Icons.Default.Security, "You stay in control",
                "Every deletion follows the same path: scan, explain why, validate, show you the plan, ask you, " +
                    "re-check each item, delete, verify, report. Only well-evidenced app caches are pre-ticked."
            )
            Fact(
                Icons.Default.Lock, "What it will not do",
                "It will not touch your photos, videos, music, documents, downloads, chat media or game saves unless " +
                    "you pick a specific file in Review. It makes no speed, RAM or battery claims, because cleaning " +
                    "storage does not do that."
            )
            Fact(
                Icons.Default.Security, "About Shizuku",
                "Shizuku is optional and is not root. It only lets CleanForge reach other apps' cache folders that " +
                    "Android 11+ hides from normal apps. Deleted files cannot be restored: there is no recycle bin."
            )
        }
    }
}

@Composable
private fun Fact(icon: ImageVector, title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
