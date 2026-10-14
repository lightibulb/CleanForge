package com.yourpackage.cleanforge.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.yourpackage.cleanforge.ui.theme.LocalWarmPalette
import kotlinx.coroutines.delay

@Composable
fun WarmToastHost(message: String?, onDismiss: () -> Unit) {
    val palette = LocalWarmPalette.current
    LaunchedEffect(message) {
        if (message != null) {
            delay(2200)
            onDismiss()
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn(tween(200)) + slideInVertically(spring(0.5f, 700f)) { it / 2 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { it / 2 },
        ) {
            Text(
                text = message.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                color = palette.toastText,
                modifier = Modifier
                    .padding(bottom = 100.dp, start = 24.dp, end = 24.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(palette.toastBg)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}
