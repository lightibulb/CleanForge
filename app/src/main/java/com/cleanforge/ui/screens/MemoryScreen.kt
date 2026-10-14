
package com.yourpackage.cleanforge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yourpackage.cleanforge.data.formatBytes
import com.yourpackage.cleanforge.ui.components.RamGauge
import com.yourpackage.cleanforge.ui.components.WarmButton
import com.yourpackage.cleanforge.ui.components.WarmBtnStyle
import com.yourpackage.cleanforge.ui.components.WarmCard
import com.yourpackage.cleanforge.ui.theme.LocalWarmPalette

@Composable
fun MemoryScreen(
    usedBytes: Long,
    totalBytes: Long,
    availableBytes: Long,
    lastFreedLabel: String?,
    clearing: Boolean,
    onClear: () -> Unit,
) {
    val palette = LocalWarmPalette.current
    val usedFraction = (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        RamGauge(usedFraction = usedFraction)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${formatBytes(usedBytes)} used of ${formatBytes(totalBytes)}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = palette.ink,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatBytes(availableBytes)} available",
                style = MaterialTheme.typography.bodySmall,
                color = palette.ink2,
            )
        }
        WarmButton(
            text = if (clearing) "Clearing…" else "Clear background apps",
            onClick = onClear,
            style = WarmBtnStyle.Primary,
            enabled = !clearing,
        )
        if (lastFreedLabel != null) {
            Text(
                lastFreedLabel,
                style = MaterialTheme.typography.bodySmall,
                color = palette.ink2,
                textAlign = TextAlign.Center,
            )
        }
    }

    WarmCard(bg = palette.surface2, border = palette.line2, padding = 18.dp) {
        Text(
            "Honest memory info. CleanForge reads /proc/meminfo directly — no fake \"boost\" claims. Clearing background apps may free some RAM, but Android will reload them when needed, which uses more battery. The number above is the actual freed bytes, never an estimate.",
            style = MaterialTheme.typography.bodySmall,
            color = palette.ink2,
        )
    }
}
