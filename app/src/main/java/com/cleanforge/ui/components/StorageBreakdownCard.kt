package com.cleanforge.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cleanforge.domain.usecase.StorageBreakdown
import com.cleanforge.domain.usecase.StorageCategory
import com.cleanforge.ui.StorageUi
import com.cleanforge.ui.formatBytes
import com.cleanforge.ui.theme.LocalWarmPalette

/**
 * The card tucked behind the storage hero: it slides down from under it when the hero is tapped. Each category can
 * be opened again for its sub-categories (folders, apps, cache types).
 */
@Composable
fun StorageBreakdownCard(storage: StorageUi, onRetry: () -> Unit) {
    val palette = LocalWarmPalette.current
    var openKey by remember { mutableStateOf<String?>(null) }

    AnimatedVisibility(
        visible = storage.expanded,
        enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = 300f)) + fadeIn(tween(220)),
        exit = shrinkVertically(tween(260)) + fadeOut(tween(160)),
    ) {
        // Pulled up so its top edge starts behind the hero (the hero is drawn above it).
        Box(Modifier.offset(y = (-24).dp)) {
            WarmCard(bg = palette.surface2, border = palette.line2, padding = 18.dp) {
                Spacer(Modifier.height(22.dp))
                val breakdown = storage.breakdown
                when {
                    breakdown == null && storage.loading ->
                        Text("Measuring…", style = MaterialTheme.typography.bodyMedium, color = palette.ink3)
                    breakdown == null -> {
                        Text(
                            storage.error ?: "Nothing measured yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.ink2,
                        )
                        Spacer(Modifier.height(10.dp))
                        WarmButton(text = "Try again", onClick = onRetry, style = WarmBtnStyle.Soft, small = true)
                    }
                    else -> BreakdownList(breakdown, openKey) { key -> openKey = if (openKey == key) null else key }
                }
            }
        }
    }
}

@Composable
private fun BreakdownList(breakdown: StorageBreakdown, openKey: String?, onToggle: (String) -> Unit) {
    val palette = LocalWarmPalette.current
    val max = breakdown.categories.maxOfOrNull { it.bytes }?.coerceAtLeast(1L) ?: 1L
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        breakdown.categories.forEach { cat ->
            CategoryRow(cat, fraction = cat.bytes.toFloat() / max, open = openKey == cat.key) { onToggle(cat.key) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("System and other", style = MaterialTheme.typography.bodyMedium, color = palette.ink)
                Text(
                    "Android's own files and anything apps cannot see.",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.ink3,
                )
            }
            Text(formatBytes(breakdown.otherBytes), style = MaterialTheme.typography.bodyMedium, color = palette.ink2)
        }
    }
}

@Composable
private fun CategoryRow(cat: StorageCategory, fraction: Float, open: Boolean, onClick: () -> Unit) {
    val palette = LocalWarmPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize(spring(dampingRatio = 0.9f, stiffness = 400f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                cat.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = palette.ink,
            )
            Text(formatBytes(cat.bytes), style = MaterialTheme.typography.bodyMedium, color = palette.ink2)
            Spacer(Modifier.width(8.dp))
            Text(if (open) "▴" else "▾", color = palette.ink3)
        }
        Spacer(Modifier.height(6.dp))
        Bar(fraction)
        if (open) {
            Spacer(Modifier.height(10.dp))
            if (cat.slices.isEmpty()) {
                Text("Nothing to list.", style = MaterialTheme.typography.bodySmall, color = palette.ink3)
            }
            val sliceMax = cat.slices.maxOfOrNull { it.bytes }?.coerceAtLeast(1L) ?: 1L
            cat.slices.forEach { s ->
                Row(Modifier.padding(start = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.label, style = MaterialTheme.typography.bodySmall, color = palette.ink)
                        Spacer(Modifier.height(3.dp))
                        Bar(s.bytes.toFloat() / sliceMax, thin = true)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(formatBytes(s.bytes), style = MaterialTheme.typography.bodySmall, color = palette.ink2)
                }
            }
            cat.note?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = palette.ink3)
            }
        }
    }
}

@Composable
internal fun Bar(fraction: Float, thin: Boolean = false) {
    val palette = LocalWarmPalette.current
    val h = if (thin) 4.dp else 6.dp
    Box(
        Modifier
            .fillMaxWidth()
            .height(h)
            .clip(RoundedCornerShape(h))
            .background(palette.line),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                .height(h)
                .clip(RoundedCornerShape(h))
                .background(if (thin) palette.sage else palette.clay),
        )
    }
}
