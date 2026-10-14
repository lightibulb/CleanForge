package com.yourpackage.cleanforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.yourpackage.cleanforge.data.Preferences
import com.yourpackage.cleanforge.ui.CleanForgeApp
import com.yourpackage.cleanforge.ui.theme.CleanForgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(0x00FFFFFF, 0x00FFFFFF),
            navigationBarStyle = SystemBarStyle.light(0x00FFFFFF, 0x00FFFFFF),
        )
        setContent {
            val context = LocalContext.current
            val prefs = remember { Preferences(context) }
            val dark by prefs.dark.collectAsState(initial = false)
            CleanForgeTheme(dark = dark) {
                CleanForgeApp(prefs = prefs)
            }
        }
    }
}
