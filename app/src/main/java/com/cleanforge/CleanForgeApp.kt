package com.cleanforge

import android.app.Application
import com.cleanforge.core.shizuku.ShizukuManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class CleanForgeApp : Application() {
    @Inject lateinit var shizukuManager: ShizukuManager

    override fun onCreate() {
        super.onCreate()
        shizukuManager.register()
    }
}
