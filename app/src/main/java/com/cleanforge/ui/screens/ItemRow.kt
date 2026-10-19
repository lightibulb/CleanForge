package com.cleanforge.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
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
import com.cleanforge.core.model.ConfidenceLevel
import com.cleanforge.core.model.Reversibility
import com.cleanforge.core.model.RiskLevel
import com.cleanforge.core.model.ScanItem
import com.cleanforge.ui.sizeLabel
import com.cleanforge.ui.components.WarmCheck
import com.cleanforge.ui.theme.Fraunces
import com.cleanforge.ui.theme.LocalWarmPalette
import com.cleanforge.ui.util.Haptic
import com.cleanforge.ui.util.rememberHaptics

@Composable
fun ItemRow(item: ScanItem, selected: Boolean, onToggle: () -> Unit) {
    val palette = LocalWarmPalette.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val rowBg by animateColorAsState(
        targetValue = if (selected) palette.clayTint else androidx.compose.ui.graphics.Color.Transparent,
        animationSpec = tween(220),
        label = "rowBg",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(tween(220))
            .background(rowBg)
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
                item.reasonForFlagging,
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
                        ConfidenceLevel.HIGH -> "Strong evidence"
                        ConfidenceLevel.MEDIUM -> "Some evidence"
                        ConfidenceLevel.LOW -> "Weak evidence"
                        ConfidenceLevel.UNKNOWN -> "Not verified"
                    }
                )
                if (item.reversibility == Reversibility.NOT_REVERSIBLE) NeutralTag("No undo")
                if (item.requiresShizuku) NeutralTag("Shizuku")
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            item.sizeLabel(), // says "at least" when part of the folder could not be read
            fontFamily = Fraunces,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            color = palette.ink,
        )
    }
}

@Composable
private fun RiskTag(risk: RiskLevel) {
    val palette = LocalWarmPalette.current
    val (bg, fg) = when (risk) {
        RiskLevel.SAFE -> palette.sageSoft to palette.sage
        RiskLevel.ELEVATED -> palette.amberSoft to palette.amberDeep
        RiskLevel.DANGEROUS -> palette.brickSoft to palette.brick
    }
    val label = when (risk) {
        RiskLevel.SAFE -> "Low risk"
        RiskLevel.ELEVATED -> "Check first"
        RiskLevel.DANGEROUS -> "May be personal"
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
