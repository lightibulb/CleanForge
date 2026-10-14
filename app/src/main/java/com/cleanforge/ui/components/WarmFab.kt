package com.cleanforge.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yourpackage.cleanforge.ui.theme.LocalWarmPalette
import com.yourpackage.cleanforge.ui.util.Haptic
import com.yourpackage.cleanforge.ui.util.rememberHaptics

@Composable
fun WarmFab(
    visible: Boolean,
    count: Int,
    sizeLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalWarmPalette.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(250)) + slideInVertically(spring(0.5f, 500f)) { it } + scaleIn(spring(0.5f, 500f)),
        exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { it } + scaleOut(tween(200)),
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(palette.fabBg)
                .clickable(interactionSource = interaction, indication = null) {
                    haptics(Haptic.Medium)
                    onClick()
                }
                .padding(horizontal = 22.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Review",
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                color = palette.fabText,
            )
            Spacer(Modifier.width(9.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(palette.fabBadge)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(
                    count.toString(),
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.fabBadgeText,
                )
            }
            Spacer(Modifier.width(9.dp))
            Text(
                sizeLabel,
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                color = palette.fabText.copy(alpha = 0.75f),
            )
        }
    }
}
