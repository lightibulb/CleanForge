package com.yourpackage.cleanforge.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Per-letter counter animation — each letter rolls up from below with 26ms stagger. */
@Composable
fun CounterHeading(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    letterDelayMs: Long = 26L,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        text.forEachIndexed { i, ch ->
            val progress = remember(text) { Animatable(0f) }
            LaunchedEffect(text, i) {
                progress.snapTo(0f)
                delay(i * letterDelayMs)
                progress.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 500f))
            }
            Text(
                text = if (ch == ' ') "\u00A0" else ch.toString(),
                style = style,
                color = color,
                modifier = Modifier.graphicsLayer {
                    translationY = (1f - progress.value) * 48f
                    alpha = progress.value
                },
            )
        }
    }
}
