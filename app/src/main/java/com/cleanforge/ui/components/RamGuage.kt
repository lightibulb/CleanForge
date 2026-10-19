package com.cleanforge.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleanforge.ui.theme.LocalWarmPalette
import com.cleanforge.ui.theme.Fraunces

@Composable
fun RamGauge(usedFraction: Float, modifier: Modifier = Modifier) {
    val palette = LocalWarmPalette.current
    var animate by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(usedFraction) { animate = usedFraction }
    val progress by animateFloatAsState(animate, tween(1400), label = "ramArc")

    val pressureColor = when {
        usedFraction > 0.85f -> palette.brick
        usedFraction > 0.7f -> palette.clay
        usedFraction > 0.5f -> palette.amber
        else -> palette.sage
    }
    val ringColor by animateColorAsState(pressureColor, tween(500), label = "ringColor")

    val pressureLabel = when {
        usedFraction > 0.85f -> "Critical"
        usedFraction > 0.7f -> "High"
        usedFraction > 0.5f -> "Moderate"
        else -> "Low"
    }

    Box(modifier = modifier.size(180.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(180.dp)) {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2 + 1f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = palette.surface3,
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = stroke),
            )
            drawArc(
                color = ringColor,
                startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${(usedFraction * 100).toInt()}%",
                fontFamily = Fraunces,
                fontSize = 44.sp,
                color = palette.ink,
            )
            Text(
                text = pressureLabel.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = palette.ink3,
                letterSpacing = 1.6.sp,
            )
        }
    }
}

