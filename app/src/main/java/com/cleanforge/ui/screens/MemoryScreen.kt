package com.cleanforge.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.cleanforge.ui.ProcessesUi
import com.cleanforge.ui.components.Bar
import com.cleanforge.ui.components.RamGauge
import com.cleanforge.ui.components.WarmBtnStyle
import com.cleanforge.ui.components.WarmButton
import com.cleanforge.ui.components.WarmCard
import com.cleanforge.ui.formatBytes
import com.cleanforge.ui.theme.LocalWarmPalette

@Composable
fun MemoryScreen(
    usedBytes: Long,
    totalBytes: Long,
    availableBytes: Long,
    lastFreedLabel: String?,
    clearing: Boolean,
    onClear: () -> Unit,
    processes: ProcessesUi,
    onToggleProcesses: () -> Unit,
) {
    val palette = LocalWarmPalette.current
    val usedFraction = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    // The hero is drawn above (zIndex) the list, which slides down from behind it when the gauge is tapped.
    WarmCard(
        modifier = Modifier
            .zIndex(1f)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onToggleProcesses() },
        bg = palette.heroSurface,
        border = palette.line,
        radius = 28.dp,
        padding = 22.dp,
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            RamGauge(usedFraction = usedFraction)
            Spacer(Modifier.height(14.dp))
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
            Spacer(Modifier.height(10.dp))
            Text(
                if (processes.expanded) "Tap to hide what is using memory ▴" else "Tap to see what is using memory ▾",
                style = MaterialTheme.typography.labelSmall,
                color = palette.ink3,
            )
        }
    }

    AnimatedVisibility(
        visible = processes.expanded,
        enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = 300f)) + fadeIn(tween(220)),
        exit = shrinkVertically(tween(260)) + fadeOut(tween(160)),
    ) {
        Box(Modifier.offset(y = (-24).dp)) {
            WarmCard(bg = palette.surface2, border = palette.line2, padding = 18.dp) {
                Spacer(Modifier.height(22.dp))
                ProcessList(processes)
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
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
            "Honest memory info. CleanForge reads /proc/meminfo directly — no fake \"boost\" claims. Clearing background apps may free some RAM for a moment, but Android reloads what you use, which can cost battery.",
            style = MaterialTheme.typography.bodySmall,
            color = palette.ink2,
        )
    }
}

@Composable
private fun ProcessList(processes: ProcessesUi) {
    val palette = LocalWarmPalette.current
    Column(Modifier.animateContentSize(spring(dampingRatio = 0.9f, stiffness = 400f))) {
        Text(
            "Using memory now",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = palette.ink,
        )
        Text(
            "Per app, from the Shizuku service. Includes memory shared with the system, so it can add up to more than the total.",
            style = MaterialTheme.typography.bodySmall,
            color = palette.ink3,
        )
        Spacer(Modifier.height(12.dp))
        when {
            processes.message != null && processes.apps.isEmpty() ->
                Text(
                    processes.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.ink2,
                )
            processes.loading && processes.apps.isEmpty() ->
                Text("Reading…", style = MaterialTheme.typography.bodySmall, color = palette.ink3)
            processes.apps.isEmpty() ->
                Text("No app processes found.", style = MaterialTheme.typography.bodySmall, color = palette.ink3)
            else -> {
                val max = processes.apps.maxOf { it.bytes }.coerceAtLeast(1L)
                processes.apps.forEach { app ->
                    Column(Modifier.padding(bottom = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                app.label,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.ink,
                                maxLines = 1,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                formatBytes(app.bytes),
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.ink2,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Bar(app.bytes.toFloat() / max)
                    }
                }
            }
        }
    }
}
