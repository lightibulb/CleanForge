package com.yourpackage.cleanforge.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yourpackage.cleanforge.ui.theme.LocalWarmPalette
import com.yourpackage.cleanforge.ui.util.Haptic
import com.yourpackage.cleanforge.ui.util.rememberHaptics

@Composable
fun WarmSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalWarmPalette.current
    val haptics = rememberHaptics()
    val track by animateColorAsState(
        targetValue = if (checked) palette.clay else palette.surface3,
        label = "track",
    )
    val border by animateColorAsState(
        targetValue = if (checked) palette.clay else palette.line,
        label = "border",
    )
    val thumbColor by animateColorAsState(
        targetValue = if (checked) palette.tickOnClay else Color.White,
        label = "thumb",
    )
    val thumbPos by animateFloatAsState(
        targetValue = if (checked) 18f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 700f),
        label = "thumbPos",
    )
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .size(width = 46.dp, height = 28.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(track)
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) {
                haptics(Haptic.Light)
                onCheckedChange(!checked)
            },
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = (2 + thumbPos).dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(thumbColor),
        )
    }
}
