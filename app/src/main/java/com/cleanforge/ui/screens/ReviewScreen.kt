package com.cleanforge.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cleanforge.core.model.ScanItem
import com.cleanforge.ui.CleanUiState
import com.cleanforge.ui.components.WarmButton
import com.cleanforge.ui.components.WarmBtnStyle
import com.cleanforge.ui.components.WarmCard
import com.cleanforge.ui.theme.LocalWarmPalette

@Composable
fun ReviewScreen(
    ui: CleanUiState,
    items: List<ScanItem>,
    onToggle: (String) -> Unit,
    onScan: () -> Unit,
    onCancelScan: () -> Unit,
    onSelectEverything: () -> Unit,
) {
    val palette = LocalWarmPalette.current

    WarmCard(bg = palette.surface2, border = palette.line2, padding = 18.dp) {
        Text(
            "These tools only suggest things for you to look at. Large, old or duplicated files are often exactly what you want to keep — so none of them are ticked for you.",
            style = MaterialTheme.typography.bodySmall,
            color = palette.ink2,
        )
    }

    Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
        Text(
            "Duplicate detection reads file contents on this phone — nothing is uploaded — and can take a few minutes on a lot of photos or videos.",
            style = MaterialTheme.typography.bodySmall,
            color = palette.ink2,
        )
        Spacer(Modifier.height(14.dp))
        WarmButton(
            text = if (ui.scanning) "Scanning…" else "Find large files & duplicates",
            onClick = onScan,
            style = WarmBtnStyle.Clay,
            enabled = !ui.busy,
        )
        if (ui.scanning) {
            Spacer(Modifier.height(10.dp))
            if (ui.scanLabel.isNotBlank()) {
                Text(ui.scanLabel, style = MaterialTheme.typography.bodySmall, color = palette.ink2)
                Spacer(Modifier.height(8.dp))
            }
            WarmButton(text = "Stop", onClick = onCancelScan, style = WarmBtnStyle.Outline, small = true)
        }
        if (items.isNotEmpty() && !ui.busy) {
            Spacer(Modifier.height(16.dp))
            WarmButton(
                text = "Select everything & delete…",
                onClick = onSelectEverything,
                style = WarmBtnStyle.Danger,
                small = true,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Ticks every suggestion below (one copy of each duplicate stays). You still see every path and confirm before anything is removed.",
                style = MaterialTheme.typography.bodySmall,
                color = palette.ink3,
            )
        }
    }

    Notices(ui.notices) // these were passed in before but never shown
    ScanSummary(ui.scanSummary)

    ResultsSection(
        items = items,
        selected = ui.selected,
        onToggle = onToggle,
        scanning = ui.scanning,
        scanLabel = ui.scanLabel,
        scanned = ui.scanned,
        emptyVerse = null,
    )
}
