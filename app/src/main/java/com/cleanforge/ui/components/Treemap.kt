package com.cleanforge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleanforge.ui.theme.Fraunces
import com.cleanforge.ui.theme.LocalWarmPalette
import com.cleanforge.ui.treemap.BoxSize
import com.cleanforge.ui.treemap.SquarifiedTreemap
import com.cleanforge.ui.treemap.TreemapItem

@Composable
fun Treemap(
    items: List<TreemapItem>,
    color: (TreemapItem) -> Color,
    onTap: (TreemapItem) -> Unit,
    modifier: Modifier = Modifier,
    minLabelArea: Float = 900f
) {
    val palette = LocalWarmPalette.current
    BoxWithConstraints(modifier) {
        val w = maxWidth.value
        val h = maxHeight.value
        val cells = remember(items, w, h) {
            SquarifiedTreemap.layout(items, BoxSize(w, h))
        }
        Box(Modifier.fillMaxSize()) {
            cells.forEach { c ->
                val area = c.width * c.height
                Box(
                    Modifier
                        .offset(c.x.dp, c.y.dp)
                        .size(c.width.dp, c.height.dp)
                        .padding(1.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(color(c.item))
                        .clickable { onTap(c.item) },
                    contentAlignment = Alignment.Center
                ) {
                    if (area >= minLabelArea) {
                        Text(
                            c.item.label,
                            color = Color.White,
                            fontFamily = Fraunces,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (cells.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nothing here yet.", color = palette.ink3, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
