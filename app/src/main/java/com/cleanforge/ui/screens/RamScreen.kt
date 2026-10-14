package com.cleanforge.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleanforge.core.memory.MemoryPressure
import com.cleanforge.core.memory.RamSnapshot
import com.cleanforge.ui.RamViewModel

@Composable
fun RamScreen(
    viewModel: RamViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Read /proc/meminfo only while the screen is visible: stops when the app goes to the background.
    LifecycleStartEffect(viewModel) {
        viewModel.startObserving()
        onStopOrDispose { viewModel.stopObserving() }
    }

    val snapshot = state.snapshot

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Unknown is shown as unknown, never a fake zero.
        if (snapshot == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Reading memory info…", style = MaterialTheme.typography.bodyLarge)
            }
            return@Column
        }

        RamGauge(snapshot)
        Spacer(Modifier.height(16.dp))

        Text(
            "${formatBytes(snapshot.usedBytes)} used of ${formatBytes(snapshot.totalBytes)}",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            "${formatBytes(snapshot.availableBytes)} available",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        Button(onClick = { viewModel.clear() }, enabled = !state.isClearing) {
            if (state.isClearing) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("Clear background apps")
        }

        state.lastClearResult?.let { result ->
            Spacer(Modifier.height(12.dp))
            Text(
                result.note,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(24.dp))

        // Honest explainer, always visible.
        Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
            Text(
                "Android keeps apps in RAM on purpose so they reopen quickly. " +
                    "Clearing frees memory only for a while and can use more battery. " +
                    "CleanForge never claims a \"boost\".",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RamGauge(snapshot: RamSnapshot) {
    Box(contentAlignment = Alignment.Center) {
        @Suppress("DEPRECATION")
        CircularProgressIndicator(
            progress = snapshot.usedFraction.coerceIn(0f, 1f),
            modifier = Modifier.size(140.dp),
            strokeWidth = 12.dp,
            color = pressureColor(snapshot.pressure),
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${(snapshot.usedFraction * 100).toInt()}%",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text("in use", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun pressureColor(pressure: MemoryPressure): Color = when (pressure) {
    MemoryPressure.LOW -> Color(0xFF2E7D32)
    MemoryPressure.NORMAL -> Color(0xFF1976D2)
    MemoryPressure.HIGH -> Color(0xFFEF6C00)
    MemoryPressure.CRITICAL -> Color(0xFFC62828)
}

private fun formatBytes(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) String.format("%.1f GB", gb)
    else String.format("%.0f MB", bytes / (1024.0 * 1024.0))
}