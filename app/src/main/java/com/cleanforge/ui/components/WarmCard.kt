package com.cleanforge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cleanforge.ui.theme.LocalWarmPalette

@Composable
fun WarmCard(
    modifier: Modifier = Modifier,
    bg: Color? = null,
    border: Color? = null,
    radius: Dp = 22.dp,
    padding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalWarmPalette.current
    val bgColor = bg ?: palette.surface
    val borderColor = border ?: palette.line2
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(radius))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(radius))
            .padding(padding),
        content = content,
    )
}
