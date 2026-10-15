package com.cleanforge.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import com.cleanforge.ui.theme.LocalWarmPalette

@Composable
fun WarmCheck(checked: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalWarmPalette.current
    val scale by animateFloatAsState(
        targetValue = if (checked) 1.05f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 700f),
        label = "scale",
    )
    val bg by animateColorAsState(
        targetValue = if (checked) palette.clay else palette.surface,
        animationSpec = tween(200), label = "bg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (checked) palette.clay else palette.line,
        animationSpec = tween(200), label = "border",
    )
    val tickProgress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(300), label = "tick",
    )

    Box(
        modifier = modifier
            .size(22.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(7.dp))
            .background(bg)
            .border(1.5.dp, borderColor, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (tickProgress > 0f) {
            Canvas(modifier = Modifier.size(13.dp)) {
                val path = Path().apply {
                    moveTo(size.width * 0.15f, size.height * 0.5f)
                    lineTo(size.width * 0.4f, size.height * 0.82f)
                    lineTo(size.width * 0.88f, size.height * 0.18f)
                }
                val pm = PathMeasure().apply { setPath(path, false) }
                val dst = Path()
                pm.getSegment(0f, pm.length * tickProgress, dst, true)
                drawPath(
                    path = dst,
                    color = palette.tickOnClay,
                    style = Stroke(
                        width = 3f,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
        }
    }
}
