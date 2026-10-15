package com.cleanforge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * The Warm look. [ProvideWarmPalette] animates the palette; the Material colour scheme is derived from it, so every
 * Material piece (the confirmation dialogs, the Shizuku status card) wears the same colours as the Warm components.
 */
@Composable
fun CleanForgeTheme(dark: Boolean, content: @Composable () -> Unit) {
    ProvideWarmPalette(dark) {
        val p = LocalWarmPalette.current
        val base = if (dark) darkColorScheme() else lightColorScheme()
        val scheme = base.copy(
            primary = p.clay,
            onPrimary = p.tickOnClay,
            primaryContainer = p.claySoft,
            onPrimaryContainer = p.clay2,
            inversePrimary = p.clay2,
            secondary = p.sage,
            onSecondary = p.tickOnClay,
            secondaryContainer = p.sageSoft,
            onSecondaryContainer = p.ink,
            tertiary = p.amber,
            onTertiary = if (dark) p.tickOnClay else p.amberDeep,
            tertiaryContainer = p.amberSoft,
            onTertiaryContainer = p.amberDeep,
            background = p.bg,
            onBackground = p.ink,
            surface = p.surface,
            onSurface = p.ink,
            surfaceVariant = p.surface3,
            onSurfaceVariant = p.ink2,
            surfaceTint = p.clay,
            inverseSurface = p.toastBg,
            inverseOnSurface = p.toastText,
            error = p.brick,
            onError = p.tickOnClay,
            errorContainer = p.brickSoft,
            onErrorContainer = p.brickDeep,
            outline = p.ink3,
            outlineVariant = p.line,
            surfaceDim = p.bg2,
            surfaceBright = p.surface,
            surfaceContainerLowest = p.surface,
            surfaceContainerLow = p.surface2,
            surfaceContainer = p.surface3,
            surfaceContainerHigh = p.bg2,
            surfaceContainerHighest = p.line
        )
        MaterialTheme(colorScheme = scheme, typography = WarmTypography, content = content)
    }
}
