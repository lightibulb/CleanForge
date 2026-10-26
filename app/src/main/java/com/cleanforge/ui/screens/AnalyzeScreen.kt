package com.cleanforge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cleanforge.core.inventory.TypeClassifier
import com.cleanforge.ui.AnalyzeUiState
import com.cleanforge.ui.components.Treemap
import com.cleanforge.ui.components.WarmBtnStyle
import com.cleanforge.ui.components.WarmButton
import com.cleanforge.ui.components.WarmCard
import com.cleanforge.ui.formatBytes
import com.cleanforge.ui.theme.Fraunces
import com.cleanforge.ui.theme.LocalWarmPalette
import com.cleanforge.ui.treemap.TreemapItem

@Composable
fun AnalyzeScreen(
    ui: AnalyzeUiState,
    onOpen: (String) -> Unit,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onSelectRange: (Long, Long) -> Unit
) {
    val palette = LocalWarmPalette.current

    WarmCard(bg = palette.heroSurface, border = palette.line, radius = 28.dp, padding = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Where the space went", fontFamily = Fraunces,
                    style = MaterialTheme.typography.titleMedium, color = palette.ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (ui.index?.incomplete == true) "At least — part of the tree could not be read."
                    else "Every number below is a measured sum of file sizes.",
                    style = MaterialTheme.typography.bodySmall, color = palette.ink3,
                )
            }
            WarmButton(text = if (ui.loading) "Scanning…" else "Refresh",
                onClick = onRefresh, style = WarmBtnStyle.Soft, small = true, enabled = !ui.loading)
        }
        Spacer(Modifier.height(12.dp))
        TreemapBox(ui, onOpen, onUp)
        Spacer(Modifier.height(12.dp))
        ChildrenList(ui, onOpen)
    }

    Spacer(Modifier.height(10.dp))

    if (ui.snapshots.size >= 2) {
        WarmCard(bg = palette.surface2, border = palette.line2, padding = 18.dp) {
            Text("What is growing", fontFamily = Fraunces,
                style = MaterialTheme.typography.titleMedium, color = palette.ink)
            Spacer(Modifier.height(4.dp))
            Text(
                if (ui.growth?.lowerBound == true) "At least — a previous snapshot was incomplete."
                else "Difference between the two selected snapshots.",
                style = MaterialTheme.typography.bodySmall, color = palette.ink3,
            )
            Spacer(Modifier.height(10.dp))
            RangePicker(ui, onSelectRange)
            Spacer(Modifier.height(10.dp))
            GrowthList(ui)
        }
    } else {
        WarmCard(bg = palette.surface2, border = palette.line2, padding = 18.dp) {
            Text("Storage history", fontFamily = Fraunces,
                style = MaterialTheme.typography.titleMedium, color = palette.ink)
            Spacer(Modifier.height(4.dp))
            Text(
                "Run the scan twice (on different days, ideally) and this card will show which folders and file types grew.",
                style = MaterialTheme.typography.bodySmall, color = palette.ink3,
            )
        }
    }
}

@Composable
private fun TreemapBox(ui: AnalyzeUiState, onOpen: (String) -> Unit, onUp: () -> Unit) {
    val palette = LocalWarmPalette.current
    val index = ui.index
    if (index == null) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.4f).clip(RoundedCornerShape(16.dp)).background(palette.surface3),
            contentAlignment = Alignment.Center
        ) {
            Text(ui.error ?: "Measuring…", style = MaterialTheme.typography.bodySmall, color = palette.ink3)
        }
        return
    }
    val here = if (ui.currentPath == index.rootPath) "Top-level folders" else ui.currentPath.removePrefix(index.rootPath + "/")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (ui.currentPath != index.rootPath) {
            Text("‹ up", modifier = Modifier.clickable { onUp() },
                style = MaterialTheme.typography.bodySmall, color = palette.clay)
            Spacer(Modifier.width(10.dp))
        }
        Text(here, style = MaterialTheme.typography.labelSmall, color = palette.ink3, maxLines = 1)
    }
    Spacer(Modifier.height(8.dp))
    val items = remember(index, ui.currentPath) {
        index.childrenOf(ui.currentPath).map { TreemapItem(it.path, it.name, it.bytes) }
    }
    val p = palette
    Treemap(
        items = items,
        color = { item ->
            val idx = items.indexOf(item).coerceAtLeast(0)
            when (idx % 4) {
                0 -> p.clay
                1 -> p.sage
                2 -> p.amber
                else -> p.brick
            }
        },
        onTap = { onOpen(it.key) },
        modifier = Modifier.fillMaxWidth().aspectRatio(1.4f)
    )
    Spacer(Modifier.height(10.dp))
    index.categoryTotals.take(5).forEach { c ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(TypeClassifier.label(c.key), modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, color = palette.ink)
            Text(formatBytes(c.bytes) + if (!c.complete) " (≥)" else "",
                style = MaterialTheme.typography.bodySmall, color = palette.ink2)
        }
    }
}

@Composable
private fun ChildrenList(ui: AnalyzeUiState, onOpen: (String) -> Unit) {
    val palette = LocalWarmPalette.current
    val index = ui.index ?: return
    val kids = index.childrenOf(ui.currentPath)
    if (kids.isEmpty()) return
    Spacer(Modifier.height(6.dp))
    Text("Biggest here", style = MaterialTheme.typography.labelSmall, color = palette.ink3)
    kids.take(8).forEach { k ->
        Row(
            Modifier.fillMaxWidth().clickable { onOpen(k.path) }.padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(k.name, modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, color = palette.ink, maxLines = 1)
            Text(formatBytes(k.bytes) + if (k.isLowerBound) " (≥)" else "",
                style = MaterialTheme.typography.bodySmall, color = palette.ink2)
        }
    }
}

@Composable
private fun RangePicker(ui: AnalyzeUiState, onSelect: (Long, Long) -> Unit) {
    val palette = LocalWarmPalette.current
    val snaps = ui.snapshots
    if (snaps.size < 2) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            snaps.takeLast(4).forEach { s ->
                val selected = s.timestamp == ui.fromTs || s.timestamp == ui.toTs
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (selected) palette.clay else palette.surface3)
                        .clickable {
                            val a = s.timestamp
                            val b = ui.toTs ?: snaps.last().timestamp
                            if (a < b) onSelect(a, b) else onSelect(b, a)
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        shortDate(s.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) Color.White else palette.ink2
                    )
                }
            }
        }
    }
}

@Composable
private fun GrowthList(ui: AnalyzeUiState) {
    val palette = LocalWarmPalette.current
    val report = ui.growth ?: return
    report.categories.take(5).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(row.name, modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, color = palette.ink, maxLines = 1)
            val sign = if (row.deltaBytes >= 0) "+" else "−"
            val color = if (row.deltaBytes >= 0) palette.brick else palette.sage
            Text("$sign${formatBytes(kotlin.math.abs(row.deltaBytes))}",
                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = color)
        }
    }
}

private fun shortDate(ms: Long): String {
    val d = java.util.Date(ms)
    return java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(d)
}
