package com.cleanforge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cleanforge.BuildConfig

@Composable
fun AboutScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("CleanForge", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Version ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("Created by Aman. Free and open source.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))

        AboutCard(
            "Private by design",
            "CleanForge works fully offline and does not ask for the internet permission. No ads, no accounts, " +
                "no analytics. Nothing leaves this phone."
        )
        AboutCard(
            "You stay in control",
            "Scans only suggest. Nothing is deleted until you have seen the exact paths and confirmed. Deleted files " +
                "are gone for good: there is no recycle bin."
        )
        AboutCard(
            "What Shizuku can and cannot do",
            "On Android 11 and newer, Shizuku lets CleanForge read and clean other apps' folders in Android/data and " +
                "Android/obb. It is not root and unlocks nothing else."
        )
        AboutCard(
            "About clearing memory",
            "Android keeps apps in memory on purpose so they reopen quickly. Clearing frees RAM only for a while and " +
                "can use more battery. CleanForge never promises a speed boost."
        )
    }
}

@Composable
private fun AboutCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
