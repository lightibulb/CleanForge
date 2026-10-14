package com.cleanforge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourpackage.cleanforge.data.Confidence
import com.yourpackage.cleanforge.data.Reversibility
import com.yourpackage.cleanforge.data.Risk
import com.yourpackage.cleanforge.data.ScanItem
import com.yourpackage.cleanforge.data.formatBytes
import com.yourpackage.cleanforge.ui.components.WarmCheck
import com.yourpackage.cleanforge.ui.theme.Fraunces
import com.yourpackage.cleanforge.ui.theme.LocalWarmPalette
import com.yourpackage.cleanforge.ui.util.Haptic
import com.yourpackage.cleanforge.ui.util.rememberHaptics

@Composable
fun ItemRow(item: ScanItem, selected: Boolean, onToggle: () -> Unit) {
    val palette = LocalWarmPalette.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) palette.clayTint else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(interactionSource = interaction, indication = null) {
                haptics(if (selected) Haptic.Tick else Haptic.Light)
                onToggle()
            }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        WarmCheck(checked = selected)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = palette.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                item.reason,
                style = MaterialTheme.typography.bodySmall,
                color = palette.ink2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                item.path,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = palette.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                RiskTag(item.risk)
                NeutralTag(
                    when (item.confidence) {
                        Confidence.HIGH -> "Strong evidence"
                        Confidence.MEDIUM -> "Some evidence"
                        Confidence.LOW -> "Weak evidence"
                    }
                )
                if (item.reversibility == Reversibility.NOT_REVERSIBLE) NeutralTag("No undo")
                if (item.requiresShizuku) NeutralTag("Shizuku")
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            formatBytes(item.sizeBytes),
            fontFamily = Fraunces,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            color = palette.ink,
        )
    }
}

@Composable
private fun RiskTag(risk: Risk) {
    val palette = LocalWarmPalette.current
    val (bg, fg) = when (risk) {
        Risk.SAFE -> palette.sageSoft to palette.sage
        Risk.ELEVATED -> palette.amberSoft to palette.amberDeep
        Risk.DANGEROUS -> palette.brickSoft to palette.brick
    }
    val label = when (risk) {
        Risk.SAFE -> "Low risk"
        Risk.ELEVATED -> "Check first"
        Risk.DANGEROUS -> "May be personal"
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun NeutralTag(text: String) {
    val palette = LocalWarmPalette.current
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = palette.ink2,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(palette.surface3)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
