package com.cleanforge.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.cleanforge.ui.theme.LocalWarmPalette

@Composable
fun HeroRing(usedFraction: Float, modifier: Modifier = Modifier) {
    val palette = LocalWarmPalette.current
    var animate by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(usedFraction) { animate = usedFraction }
    val progress by animateFloatAsState(
        targetValue = animate,
        animationSpec = tween(1400),
        label = "heroArc",
    )

    Box(modifier = modifier.size(100.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(100.dp)) {
            val stroke = 7.dp.toPx()
            val inset = stroke / 2 + 1f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            // track
            drawArc(
                color = palette.surface3,
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = stroke),
            )
            // progress
            drawArc(
                color = palette.clay,
                startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Text(
            text = "${(usedFraction * 100).toInt()}%",
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp),
            ),
            color = palette.ink,
        )
    }
}

