package com.cleanforge.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.cleanforge.ui.theme.LocalWarmPalette
import com.cleanforge.ui.util.Haptic
import com.cleanforge.ui.util.rememberHaptics

data class DockItem(val key: String, val label: String, val icon: ImageVector)

@Composable
fun WarmDock(
    items: List<DockItem>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalWarmPalette.current
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    var itemOffsets by remember { mutableStateOf(List(items.size) { 0f }) }
    var itemWidths by remember { mutableStateOf(List(items.size) { 0f }) }

    val selectedIndex = items.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
    val targetOffset = itemOffsets.getOrElse(selectedIndex) { 0f }
    val targetWidth = itemWidths.getOrElse(selectedIndex) { 0f }

    val pillX by animateFloatAsState(
        targetValue = targetOffset,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 320f),
        label = "pillX",
    )
    val pillW by animateFloatAsState(
        targetValue = targetWidth,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 320f),
        label = "pillW",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .shadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(999.dp),
                    ambientColor = Color(0x33000000),
                    spotColor = Color(0x55000000),
                )
                .clip(RoundedCornerShape(999.dp))
                .background(palette.dockBg)
                .border(1.dp, palette.dockBorder, RoundedCornerShape(999.dp)),
        )
        Box(
            modifier = Modifier
                .padding(start = 6.dp, top = 6.dp, bottom = 6.dp)
                .offset { IntOffset(pillX.toInt(), 0) }
                .width(with(density) { pillW.toDp() })
                .height(52.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(palette.claySoft),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { i, item ->
                val selected = item.key == selectedKey
                val tint by animateColorAsState(
                    targetValue = if (selected) palette.clay2 else palette.ink3,
                    label = "tint",
                )
                val iconScale by animateFloatAsState(
                    targetValue = if (selected) 1.08f else 1f,
                    animationSpec = spring(dampingRatio = 0.4f, stiffness = 700f),
                    label = "iconScale",
                )
                val interaction = remember { MutableInteractionSource() }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(999.dp))
                        .onGloballyPositioned { c ->
                            itemOffsets = itemOffsets.toMutableList().also {
                                it[i] = c.positionInParent().x
                            }
                            itemWidths = itemWidths.toMutableList().also {
                                it[i] = c.size.width.toFloat()
                            }
                        }
                        .clickable(interactionSource = interaction, indication = null) {
                            if (!selected) {
                                haptics(Haptic.Light)
                                onSelect(item.key)
                            }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = tint,
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer { scaleX = iconScale; scaleY = iconScale },
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.label,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = tint,
                    )
                }
            }
        }
    }
}
