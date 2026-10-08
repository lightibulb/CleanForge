package com.cleanforge.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Full hand-tuned green scheme (seed #006D3B). Used below Android 12 (API 31), where Material You
// dynamic colour does not exist, e.g. on the target Vivo Y11 (Android 11). Regenerate with Material Theme Builder if desired.
private val LightColors = lightColorScheme(
    primary = Color(0xFF006D3B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF97F7B5),
    onPrimaryContainer = Color(0xFF00210F),
    inversePrimary = Color(0xFF7ADA9A),
    secondary = Color(0xFF4E6355),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD0E8D5),
    onSecondaryContainer = Color(0xFF0B1F13),
    tertiary = Color(0xFF3A656F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBEEAF6),
    onTertiaryContainer = Color(0xFF001F26),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6FBF3),
    onBackground = Color(0xFF181D18),
    surface = Color(0xFFF6FBF3),
    onSurface = Color(0xFF181D18),
    surfaceVariant = Color(0xFFDDE5DB),
    onSurfaceVariant = Color(0xFF414941),
    outline = Color(0xFF717971),
    outlineVariant = Color(0xFFC1C9BF),
    inverseSurface = Color(0xFF2D322D),
    inverseOnSurface = Color(0xFFEDF2EA),
    surfaceTint = Color(0xFF006D3B),
    surfaceDim = Color(0xFFD7DBD3),
    surfaceBright = Color(0xFFF6FBF3),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5ED),
    surfaceContainer = Color(0xFFEBEFE7),
    surfaceContainerHigh = Color(0xFFE5EAE2),
    surfaceContainerHighest = Color(0xFFDFE4DC)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7ADA9A),
    onPrimary = Color(0xFF00391D),
    primaryContainer = Color(0xFF00522B),
    onPrimaryContainer = Color(0xFF97F7B5),
    inversePrimary = Color(0xFF006D3B),
    secondary = Color(0xFFB4CCBA),
    onSecondary = Color(0xFF203527),
    secondaryContainer = Color(0xFF364B3D),
    onSecondaryContainer = Color(0xFFD0E8D5),
    tertiary = Color(0xFFA2CEDA),
    onTertiary = Color(0xFF023640),
    tertiaryContainer = Color(0xFF214D57),
    onTertiaryContainer = Color(0xFFBEEAF6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101510),
    onBackground = Color(0xFFE0E4DC),
    surface = Color(0xFF101510),
    onSurface = Color(0xFFE0E4DC),
    surfaceVariant = Color(0xFF414941),
    onSurfaceVariant = Color(0xFFC1C9BF),
    outline = Color(0xFF8B938A),
    outlineVariant = Color(0xFF414941),
    inverseSurface = Color(0xFFE0E4DC),
    inverseOnSurface = Color(0xFF2D322D),
    surfaceTint = Color(0xFF7ADA9A),
    surfaceDim = Color(0xFF101510),
    surfaceBright = Color(0xFF353A35),
    surfaceContainerLowest = Color(0xFF0B0F0B),
    surfaceContainerLow = Color(0xFF181D18),
    surfaceContainer = Color(0xFF1C211C),
    surfaceContainerHigh = Color(0xFF272B26),
    surfaceContainerHighest = Color(0xFF323630)
)

@Composable
fun CleanForgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
