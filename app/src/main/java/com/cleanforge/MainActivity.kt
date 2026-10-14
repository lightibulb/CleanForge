package com.cleanforge

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import com.cleanforge.ui.CleanForgeNavHost
import com.cleanforge.ui.theme.CleanForgeTheme
import dagger.hilt.android.AndroidEntryPoint

/** @AndroidEntryPoint is required: the screens get their ViewModels from Hilt. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            // Bar icons must contrast with the theme (they were fixed to "light" and vanished in dark mode).
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                )
                onDispose { }
            }
            CleanForgeTheme(darkTheme = dark) {
                CleanForgeNavHost()
            }
        }
    }
}
