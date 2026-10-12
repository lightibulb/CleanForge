package com.cleanforge.core.memory

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** The one place that asks Android to stop background processes. A seam so the use case is testable without the framework. */
fun interface BackgroundProcessKiller {
    /**
     * Asks Android to stop the BACKGROUND processes of [packageName] (the same thing the kernel does under memory
     * pressure; not a force-stop). Returns true if a request was sent, false if the app was deliberately skipped.
     */
    fun kill(packageName: String): Boolean
}

/**
 * Skips CleanForge itself and every system app (launcher, keyboard, dialer, system services): stopping those only
 * makes the phone reload them and frees little. When the app type cannot be determined it is skipped (fail closed).
 */
class AndroidBackgroundProcessKiller(context: Context) : BackgroundProcessKiller {
    private val appContext = context.applicationContext
    private val activityManager: ActivityManager? = appContext.getSystemService(ActivityManager::class.java)
    private val packageManager: PackageManager = appContext.packageManager

    override fun kill(packageName: String): Boolean {
        val am = activityManager ?: return false
        if (packageName == appContext.packageName) return false
        if (isSystemOrUnknown(packageName)) return false
        am.killBackgroundProcesses(packageName)
        return true
    }

    private fun isSystemOrUnknown(packageName: String): Boolean = try {
        (packageManager.getApplicationInfo(packageName, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0
    } catch (e: Exception) {
        true
    }
}
