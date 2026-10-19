package com.cleanforge.ui.screens

import com.cleanforge.ui.components.WarmBtnStyle
import com.cleanforge.ui.components.WarmButton
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cleanforge.ui.components.WarmCard
import com.cleanforge.ui.components.WarmSwitch
import com.cleanforge.ui.theme.Fraunces
import com.cleanforge.ui.theme.LocalWarmPalette
import com.cleanforge.ui.util.Haptic
import com.cleanforge.ui.util.rememberHaptics

@Composable
fun AboutScreen(
    themeLabel: String,
    onCycleTheme: () -> Unit,
    onForgeTap: () -> Unit,
) {
    val palette = LocalWarmPalette.current
    val haptics = rememberHaptics()

    // About hero — 7-tap easter egg target
    WarmCard(
        bg = palette.aboutSurface,
        border = palette.line2,
        radius = 28.dp,
        padding = 26.dp,
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) {
            haptics(Haptic.Tick)
            onForgeTap()
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(palette.clay),
                contentAlignment = Alignment.Center,
            ) {
                Text("🧹", style = MaterialTheme.typography.headlineMedium)
            }
            Spacer(Modifier.width(18.dp))
            Column {
                Text(
                    "CleanForge",
                    fontFamily = Fraunces,
                    fontWeight = FontWeight.Medium,
                    fontSize = androidx.compose.ui.unit.TextUnit(24f, androidx.compose.ui.unit.TextUnitType.Sp),
                    color = palette.ink,
                )
                Spacer(Modifier.height(4.dp))
                Text("Made by Aman", style = MaterialTheme.typography.bodyMedium, color = palette.ink2)
                Spacer(Modifier.height(6.dp))
                Text(
                    "v1.0.0 · © 2026 · free & open source",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.ink3,
                )
            }
        }
    }

    Spacer(Modifier.height(10.dp))

    // Facts
    WarmCard(bg = palette.surface, border = palette.line2) {
        Fact("◉", "No internet, no tracking",
            "CleanForge does not declare the INTERNET permission. No ads, no analytics, no accounts. Your files never leave your phone.",
            FactGlyph.Sage)
        Fact("✓", "Evidence-based cleaning",
            "Every item flagged carries proof of why it's safe to delete. Filename or extension alone is never accepted as evidence. You review and confirm everything.",
            FactGlyph.Default)
        Fact("⛨", "Safety first",
            "Fingerprint verification, symlink protection, mount boundaries, and depth limits. No shell commands, no recursive deletion. Personal data zones are never pre-selected.",
            FactGlyph.Brick)
        Fact("✦", "Shizuku integration (optional)",
            "On Android 11+, Android/data and Android/obb are invisible to normal apps. Shizuku (user-service, not root) lets CleanForge read and clear those caches. Without it, those scanners are skipped with a visible notice.",
            FactGlyph.Amber)
        Fact("◐", "Honest RAM monitoring",
            "Reads /proc/meminfo directly. Shows total, used, available, and cached memory with a colour-coded pressure indicator. No fake \"boost\" claims.",
            FactGlyph.Default)
    }

    Spacer(Modifier.height(10.dp))

    // Dark mode — permanent setting
    WarmCard(bg = palette.surface, border = palette.line2) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Theme",
                    fontFamily = Fraunces,
                    fontWeight = FontWeight.Medium,
                    fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp),
                    color = palette.ink,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Follows your phone by default. Tap to switch between System, Light and Dark.",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.ink2,
                )
            }
            WarmButton(text = themeLabel, onClick = onCycleTheme, style = WarmBtnStyle.Soft, small = true)
        }
    }
}

enum class FactGlyph { Default, Sage, Amber, Brick }

@Composable
private fun Fact(glyph: String, title: String, body: String, glyphStyle: FactGlyph) {
    val palette = LocalWarmPalette.current
    val (bg, fg) = when (glyphStyle) {
        FactGlyph.Default -> palette.clayTint to palette.clay
        FactGlyph.Sage -> palette.sageSoft to palette.sage
        FactGlyph.Amber -> palette.amberSoft to palette.amberDeep
        FactGlyph.Brick -> palette.brickSoft to palette.brick
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(bg),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, color = fg, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = palette.ink,
            )
            Spacer(Modifier.height(3.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = palette.ink2)
        }
    }
}
