package com.cleanforge.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleanforge.data.Verse
import com.cleanforge.ui.theme.Fraunces
import com.cleanforge.ui.theme.LocalWarmPalette
import kotlinx.coroutines.delay

@Composable
fun VerseHero(
    verse: Verse,
    scrollAlpha: Float,
    modifier: Modifier = Modifier,
    delayMs: Long = 0L,
) {
    val palette = LocalWarmPalette.current
    var visible by remember(verse) { mutableStateOf(false) }
    LaunchedEffect(verse) {
        delay(delayMs)
        visible = true
    }
    val fade by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(1300),
        label = "verseFade",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
            .graphicsLayer { alpha = fade * scrollAlpha },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(palette.clay.copy(alpha = 0.6f)),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = verse.text,
            fontFamily = Fraunces,
            fontStyle = FontStyle.Italic,
            fontSize = 14.5.sp,
            lineHeight = 25.sp,
            letterSpacing = (-0.005).sp,
            textAlign = TextAlign.Center,
            color = palette.ink2,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = verse.ref.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = palette.clay,
            letterSpacing = 1.8.sp,
        )
    }
}
