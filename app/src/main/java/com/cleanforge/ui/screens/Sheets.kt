package com.cleanforge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourpackage.cleanforge.data.ScanItem
import com.yourpackage.cleanforge.data.formatBytes
import com.yourpackage.cleanforge.ui.components.WarmButton
import com.yourpackage.cleanforge.ui.components.WarmBtnStyle
import com.yourpackage.cleanforge.ui.components.WarmSheet
import com.yourpackage.cleanforge.ui.theme.Fraunces
import com.yourpackage.cleanforge.ui.theme.LocalWarmPalette

@Composable
fun ConfirmSheet(
    items: List<ScanItem>,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val palette = LocalWarmPalette.current
    val total = items.sumOf { it.sizeBytes }
    WarmSheet(
        onDismiss = onCancel,
        title = "Delete ${items.size} item${if (items.size > 1) "s" else ""}?",
        subtitle = "Frees about ${formatBytes(total)} — file sizes, not disk blocks.",
    ) {
        if (items.any { it.reversibility == com.yourpackage.cleanforge.data.Reversibility.NOT_REVERSIBLE }) {
            WarnBanner("Deleted for good — CleanForge has no recycle bin.")
        }
        if (items.any { it.risk != com.yourpackage.cleanforge.data.Risk.SAFE }) {
            WarnBanner("Some items aren't plain caches and may be personal. Check every path below before confirming.")
        }

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(palette.surface2)
                .padding(0.dp)
        ) {
            items.forEach { item ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                ) {
                    Text(
                        item.path,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        ),
                        color = palette.ink,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${item.category} · ${formatBytes(item.sizeBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.ink2,
                    )
                    Text(
                        "Why flagged: ${item.reason}",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.ink2,
                    )
                    Text(
                        "Confidence: ${item.confidence} · Risk: ${item.risk}" +
                            if (item.requiresShizuku) " · via Shizuku" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.ink2,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            WarmButton("Cancel", onCancel, style = WarmBtnStyle.Ghost, small = true)
            Spacer(Modifier.padding(4.dp))
            WarmButton("Delete permanently", onConfirm, style = WarmBtnStyle.Danger, small = true)
        }
    }
}

@Composable
private fun WarnBanner(text: String) {
    val palette = LocalWarmPalette.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(palette.brickSoft)
            .padding(12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = palette.brickDeep)
    }
}

@Composable
fun ProgressSheet(progress: Float, done: Int, total: Int, freedBytes: Long, onStop: () -> Unit) {
    val palette = LocalWarmPalette.current
    WarmSheet(
        onDismiss = { /* not dismissible */ },
        title = "Cleaning…",
        subtitle = "Please keep the app open.",
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(palette.surface3),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(palette.clay),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "$done of $total · freed ${formatBytes(freedBytes)}",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.ink2,
        )
        Spacer(Modifier.height(20.dp))
        WarmButton("Stop", onStop, style = WarmBtnStyle.Ghost, small = true)
    }
}

@Composable
fun ReportSheet(freedBytes: Long, onDismiss: () -> Unit) {
    val palette = LocalWarmPalette.current
    WarmSheet(
        onDismiss = onDismiss,
        title = if (freedBytes > 0) "All done" else "Stopped",
    ) {
        if (freedBytes > 0) {
            Text(
                formatBytes(freedBytes),
                fontFamily = Fraunces,
                fontSize = 40.sp,
                color = palette.clay,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Everything you confirmed has been removed. Anything already removed stays removed.",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.ink2,
            )
        } else {
            Text(
                "Nothing was deleted. Your files are exactly where you left them.",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.ink2,
            )
        }
        Spacer(Modifier.height(20.dp))
        WarmButton("OK", onDismiss, style = WarmBtnStyle.Primary, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun EasterEggSheet(onCancel: () -> Unit, onConfirm: () -> Unit) {
    WarmSheet(
        onDismiss = onCancel,
        title = "Are you sure?",
        subtitle = "Seven taps on the forge. That's either curiosity or destiny — and I'm not here to judge. Ready to flip the verses?",
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            WarmButton("Not yet", onCancel, style = WarmBtnStyle.Ghost, small = true)
            Spacer(Modifier.padding(4.dp))
            WarmButton("Flip it", onConfirm, style = WarmBtnStyle.Primary, small = true)
        }
    }
}
