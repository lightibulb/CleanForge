package com.cleanforge

import com.cleanforge.data.ThemeMode
import androidx.compose.foundation.isSystemInDarkTheme
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleanforge.data.Preferences
import com.cleanforge.ui.CleanForgeApp
import com.cleanforge.ui.theme.CleanForgeTheme
import dagger.hilt.android.AndroidEntryPoint

/** @AndroidEntryPoint is required: the app gets its ViewModels from Hilt. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val prefs = remember { Preferences(context) }
            val mode by prefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.System)
            val dark = when (mode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }

            // Bar icons must contrast with the theme the user chose in About (they were fixed to "light").
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                )
                onDispose { }
            }

            CleanForgeTheme(dark = dark) {
                CleanForgeApp(prefs = prefs)
            }
        }
    }
}
