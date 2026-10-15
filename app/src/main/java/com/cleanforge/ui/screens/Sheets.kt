package com.cleanforge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleanforge.ui.components.WarmButton
import com.cleanforge.ui.components.WarmBtnStyle
import com.cleanforge.ui.components.WarmSheet
import com.cleanforge.ui.theme.Fraunces
import com.cleanforge.ui.theme.LocalWarmPalette

@Composable
fun EasterEggSheet(onCancel: () -> Unit, onConfirm: () -> Unit) {
    WarmSheet(
        onDismiss = onCancel,
        title = "Are you sure?",
        subtitle = "Seven taps on the forge. That's either curiosity or destiny — and I'm not here to judge. Ready to flip the verses?",
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            WarmButton("Not yet", onCancel, style = WarmBtnStyle.Ghost, small = true)
            Spacer(Modifier.padding(4.dp))
            WarmButton("Flip it", onConfirm, style = WarmBtnStyle.Primary, small = true)
        }
    }
}
