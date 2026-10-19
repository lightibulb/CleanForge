package com.cleanforge.ui.screens

import com.cleanforge.ui.components.StorageBreakdownCard
import androidx.compose.ui.zIndex
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.cleanforge.core.model.ScanItem
import com.cleanforge.ui.CleanUiState
import com.cleanforge.ui.components.ShizukuCard
import com.cleanforge.ui.formatBytes
import com.cleanforge.ui.components.HeroRing
import com.cleanforge.ui.components.WarmButton
import com.cleanforge.ui.components.WarmBtnStyle
import com.cleanforge.ui.components.VerseHero
import com.cleanforge.ui.components.WarmCard
import com.cleanforge.data.Verse
import com.cleanforge.ui.theme.Fraunces
import com.cleanforge.ui.theme.LocalWarmPalette

@Composable
fun CleanScreen(
    ui: CleanUiState,
    items: List<ScanItem>,
    onToggle: (String) -> Unit,
    onScan: () -> Unit,
    onCancelScan: () -> Unit,
    onOpenFilesSettings: () -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onRecheckShizuku: () -> Unit,
    onToggleStorage: () -> Unit,
    emptyStateVerse: Verse?,
) {
    val palette = LocalWarmPalette.current
    val freeBytes = ui.freeBytes
    val totalBytes = ui.totalBytes
    val usedFraction = ui.usedFraction

    // Storage hero
    // Drawn above the breakdown card, which slides down from behind it when the hero is tapped.
    WarmCard(
        modifier = Modifier
            .zIndex(1f)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onToggleStorage() },
        bg = palette.heroSurface,
        border = palette.line,
        radius = 28.dp,
        padding = 24.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeroRing(usedFraction = usedFraction)
            Spacer(Modifier.width(22.dp))
            Column {
                Text(
                    "AVAILABLE",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.3.sp,
                    color = palette.clay,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    formatBytes(freeBytes),
                    fontFamily = Fraunces,
                    fontSize = androidx.compose.ui.unit.TextUnit(32f, androidx.compose.ui.unit.TextUnitType.Sp),
                    letterSpacing = (-0.6).sp,
                    color = palette.ink,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "free of ${formatBytes(totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.ink2,
                )
            }
        }
            Spacer(Modifier.height(12.dp))
        Text(
            if (ui.storage.expanded) "Tap to hide the breakdown ▴" else "Tap for a breakdown by type ▾",
            style = MaterialTheme.typography.labelSmall,
            color = palette.ink3,
        )
}

    StorageBreakdownCard(storage = ui.storage, onRetry = onToggleStorage)

    Spacer(Modifier.height(10.dp))

    // All-files access: shown only while it is missing.
    if (!ui.hasAllFilesAccess) {
        Spacer(Modifier.height(10.dp))
        InfoCard(
            glyph = "◐",
            title = "Allow access to files",
            body = "CleanForge needs All-files access to list and delete things in shared storage. It only deletes " +
                "items you select and confirm, and the app has no internet permission.",
            actionText = "Open settings",
            onAction = onOpenFilesSettings,
            style = WarmBtnStyle.Soft,
        )
    }

    // Shizuku: the real state, with the real next step.
    Spacer(Modifier.height(10.dp))
    ShizukuCard(
        state = ui.shizuku,
        report = ui.capabilities,
        onRequestPermission = onRequestShizukuPermission,
        onOpenShizuku = onOpenShizuku,
        onRecheck = onRecheckShizuku,
    )

    // Scan block
    Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
        Text(
            "Looks at app caches, thumbnails, abandoned downloads, installers and leftovers of uninstalled apps. Nothing is deleted until you review and confirm.",
            style = MaterialTheme.typography.bodySmall,
            color = palette.ink2,
        )
        Spacer(Modifier.height(14.dp))
        WarmButton(
            text = if (ui.scanning) "Scanning…" else "Run scan",
            onClick = onScan,
            style = WarmBtnStyle.Primary,
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
    }

    Notices(ui.notices)
    ScanSummary(ui.scanSummary)

    // Results
    ResultsSection(
        items = items,
        selected = ui.selected,
        onToggle = onToggle,
        scanning = ui.scanning,
        scanLabel = ui.scanLabel,
        scanned = ui.scanned,
        emptyVerse = emptyStateVerse,
    )
}

/** Amber notes from the scan: what could not be checked and why. Shared by the Clean and Review tabs. */
@Composable
internal fun Notices(notices: List<String>) {
    val palette = LocalWarmPalette.current
    notices.forEach { n ->
        Box(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(palette.amberSoft)
                .padding(14.dp),
        ) {
            Row {
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(palette.amber),
                )
                Spacer(Modifier.width(10.dp))
                Text(n, style = MaterialTheme.typography.bodySmall, color = palette.amberDeep)
            }
        }
    }
}

@Composable
fun InfoCard(
    glyph: String,
    title: String,
    body: String,
    actionText: String,
    onAction: () -> Unit,
    style: WarmBtnStyle,
    sageGlyph: Boolean = false,
) {
    val palette = LocalWarmPalette.current
    WarmCard(
        bg = palette.surface2,
        border = palette.line2,
        padding = 18.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (sageGlyph) palette.sageSoft else palette.clayTint),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    glyph,
                    color = if (sageGlyph) palette.sage else palette.clay,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                fontFamily = Fraunces,
                fontWeight = FontWeight.Medium,
                fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp),
                color = palette.ink,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = palette.ink2)
        Spacer(Modifier.height(14.dp))
        WarmButton(text = actionText, onClick = onAction, style = style, small = true)
    }
}


@Composable
fun ResultsSection(
    items: List<ScanItem>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    scanning: Boolean,
    scanLabel: String = "",
    scanned: Boolean,
    emptyVerse: Verse?,
) {
    val palette = LocalWarmPalette.current
    if (scanning) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(scanLabel.ifBlank { "Scanning…" }, style = MaterialTheme.typography.bodyMedium, color = palette.ink3)
        }
        return
    }
    if (!scanned) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Nothing scanned yet. Run a scan to see what could be removed — you decide what goes.",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.ink3,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (emptyVerse != null) {
                Spacer(Modifier.height(20.dp))
                VerseHero(
                    verse = emptyVerse,
                    scrollAlpha = 1f,
                    delayMs = 400L,
                )
            }
        }
        return
    }
    if (items.isEmpty()) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Nothing found here. If a scan could not check something, it says so above.", style = MaterialTheme.typography.bodyMedium, color = palette.ink3)
        }
        return
    }

    val grouped = items.groupBy { it.category }
    grouped.forEach { (category, list) ->
        val total = list.sumOf { it.sizeBytes }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                category.label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = palette.clay,
                letterSpacing = 1.4.sp,
            )
            Text(
                "${list.size} · ${formatBytes(total)}",
                style = MaterialTheme.typography.labelSmall,
                color = palette.ink3,
            )
        }
        list.forEach { item ->
            ItemRow(
                item = item,
                selected = selected.contains(item.path),
                onToggle = { onToggle(item.path) },
            )
        }
    }
}

/** Neutral lines for the scanners that ran: what each found, so an empty list is explained. */
@Composable
internal fun ScanSummary(lines: List<String>) {
    if (lines.isEmpty()) return
    val palette = LocalWarmPalette.current
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.surface2)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("What the scan checked", style = MaterialTheme.typography.labelSmall, color = palette.ink3)
        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = palette.ink2) }
    }
}
