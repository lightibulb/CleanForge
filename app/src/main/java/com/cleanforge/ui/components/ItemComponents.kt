package com.cleanforge.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cleanforge.core.model.CleaningCategory
import com.cleanforge.core.model.ConfidenceLevel
import com.cleanforge.core.model.Reversibility
import com.cleanforge.core.model.RiskLevel
import com.cleanforge.core.model.ScanItem
import com.cleanforge.ui.formatBytes

@Composable
fun Tag(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun ItemRow(item: ScanItem, selected: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    ListItem(
        modifier = modifier.clickable(onClick = onToggle),
        leadingContent = { Checkbox(checked = selected, onCheckedChange = { onToggle() }) },
        headlineContent = { Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    item.reasonForFlagging,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                // Full path: two rows can share a file name (copies of a duplicate, several "cache" folders).
                Text(
                    item.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (item.risk) {
                        RiskLevel.SAFE -> Tag("Low risk", cs.primaryContainer, cs.onPrimaryContainer)
                        RiskLevel.ELEVATED -> Tag("Check first", cs.tertiaryContainer, cs.onTertiaryContainer)
                        RiskLevel.DANGEROUS -> Tag("May be personal", cs.errorContainer, cs.onErrorContainer)
                    }
                    Tag(evidenceLabel(item.confidence), cs.surfaceContainerHighest, cs.onSurfaceVariant)
                    if (item.reversibility == Reversibility.NOT_REVERSIBLE) {
                        Tag("No undo", cs.surfaceContainerHighest, cs.onSurfaceVariant)
                    }
                    if (item.requiresShizuku) {
                        Tag("Shizuku", cs.secondaryContainer, cs.onSecondaryContainer)
                    }
                }
            }
        },
        trailingContent = {
            Text(
                if (item.sizeBytes > 0) formatBytes(item.sizeBytes) else "0 B",
                style = MaterialTheme.typography.labelLarge
            )
        }
    )
}

private fun evidenceLabel(c: ConfidenceLevel): String = when (c) {
    ConfidenceLevel.HIGH -> "Strong evidence"
    ConfidenceLevel.MEDIUM -> "Some evidence"
    ConfidenceLevel.LOW -> "Weak evidence"
    ConfidenceLevel.UNKNOWN -> "No evidence"
}

@Composable
fun CategoryHeader(category: CleaningCategory, count: Int, bytes: Long, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            category.label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        Text(
            "$count · ${formatBytes(bytes)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Grouped result list shared by the Clean and Review screens. */
fun LazyListScope.resultsSection(
    items: List<ScanItem>,
    selected: Set<String>,
    onToggle: (String) -> Unit
) {
    val grouped = items.groupBy { it.category }
    for ((category, list) in grouped) {
        item(key = "hdr_${category.name}") {
            CategoryHeader(category, list.size, list.sumOf { it.sizeBytes })
        }
        // Keys must be unique across the whole list; one path can be flagged by two scanners (e.g. large file + duplicate).
        items(list, key = { "${it.category.name}:${it.path}" }) { scanItem ->
            ItemRow(scanItem, scanItem.path in selected, onToggle = { onToggle(scanItem.path) })
        }
    }
}
