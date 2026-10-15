package com.cleanforge.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cleanforge.ui.theme.LocalWarmPalette
import com.cleanforge.ui.util.Haptic
import com.cleanforge.ui.util.rememberHaptics

enum class WarmBtnStyle { Primary, Clay, Soft, Outline, Ghost, Danger }

@Composable
fun WarmButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: WarmBtnStyle = WarmBtnStyle.Primary,
    small: Boolean = false,
    enabled: Boolean = true,
    hapticType: Haptic = Haptic.Light,
) {
    val palette = LocalWarmPalette.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 900f),
        label = "btnScale",
    )

    val (bg, fg, border) = when (style) {
        WarmBtnStyle.Primary -> Triple(palette.fabBg, palette.fabText, Color.Transparent)
        WarmBtnStyle.Clay -> Triple(palette.clay, if (palette.bg == Color(0xFF101014)) Color(0xFF1A0F0A) else Color(0xFFFFF6EF), Color.Transparent)
        WarmBtnStyle.Soft -> Triple(palette.surface3, palette.ink, palette.line)
        WarmBtnStyle.Outline -> Triple(Color.Transparent, palette.ink, palette.line)
        WarmBtnStyle.Ghost -> Triple(Color.Transparent, palette.ink2, Color.Transparent)
        WarmBtnStyle.Danger -> Triple(Color.Transparent, palette.brick, Color.Transparent)
    }

    val hPad = if (small) 16.dp else 20.dp
    val vPad = if (small) 9.dp else 11.dp
    val fontSize = if (small) androidx.compose.ui.unit.TextUnit.Unspecified else androidx.compose.ui.unit.TextUnit.Unspecified

    Row(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .then(
                if (border != Color.Transparent)
                    Modifier.border(BorderStroke(1.dp, border), RoundedCornerShape(999.dp))
                else Modifier
            )
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
            ) {
                haptics(hapticType)
                onClick()
            }
            .padding(horizontal = hPad, vertical = vPad),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = if (small)
                androidx.compose.material3.MaterialTheme.typography.bodySmall
            else
                androidx.compose.material3.MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = if (enabled) fg else fg.copy(alpha = 0.5f),
        )
    }
}
