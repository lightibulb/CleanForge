package com.yourpackage.cleanforge.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yourpackage.cleanforge.data.ScanItem
import com.yourpackage.cleanforge.ui.components.WarmButton
import com.yourpackage.cleanforge.ui.components.WarmBtnStyle
import com.yourpackage.cleanforge.ui.components.WarmCard
import com.yourpackage.cleanforge.ui.theme.LocalWarmPalette

@Composable
fun ReviewScreen(
    scanning: Boolean,
    scanned: Boolean,
    items: List<ScanItem>,
    notices: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onScan: () -> Unit,
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
            text = if (scanning) "Scanning…" else "Find large files & duplicates",
            onClick = onScan,
            style = WarmBtnStyle.Clay,
            enabled = !scanning,
        )
    }

    ResultsSection(
        items = items,
        selected = selected,
        onToggle = onToggle,
        scanning = scanning,
        scanned = scanned,
        emptyVerse = null,
    )
}
