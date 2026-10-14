package com.cleanforge.ui.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

enum class Haptic { Light, Medium, Heavy, Tick, Success, Warn, Error }

@Composable
fun rememberHaptics(): (Haptic) -> Unit {
    val ctx = LocalContext.current
    return remember(ctx) {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            mgr.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        { type ->
            if (vibrator.hasVibrator()) {
                val effect = when (type) {
                    Haptic.Tick -> VibrationEffect.createOneShot(5, 60)
                    Haptic.Light -> VibrationEffect.createOneShot(8, 80)
                    Haptic.Medium -> VibrationEffect.createOneShot(14, 110)
                    Haptic.Heavy -> VibrationEffect.createOneShot(24, 160)
                    Haptic.Success -> VibrationEffect.createWaveform(
                        longArrayOf(0, 10, 40, 18), intArrayOf(0, 90, 0, 130), -1)
                    Haptic.Warn -> VibrationEffect.createWaveform(
                        longArrayOf(0, 16, 55, 16), intArrayOf(0, 120, 0, 120), -1)
                    Haptic.Error -> VibrationEffect.createWaveform(
                        longArrayOf(0, 26, 45, 26, 45, 26), intArrayOf(0, 150, 0, 150, 0, 150), -1)
                }
                vibrator.vibrate(effect)
            }
        }
    }
}
