package com.cleanforge.core.memory

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * The one place that asks Android to stop background processes.
 * A seam so the use case is testable without the framework.
 */
fun interface BackgroundProcessKiller {

    /**
     * Asks Android to stop the BACKGROUND processes of [packageName].
     * This is the same kind of trim Android does under memory pressure; it is not a force-stop.
     *
     * Returns true if a request was sent.
     * Returns false if the package was deliberately skipped or the OS refused the request.
     */
    fun kill(packageName: String): Boolean
}

/**
 * Skips CleanForge itself and every system app.
 * Stopping system components usually only makes Android reload them.
 * If the app type cannot be determined, it is skipped fail-closed.
 */
class AndroidBackgroundProcessKiller(context: Context) : BackgroundProcessKiller {

    private val appContext = context.applicationContext
    private val activityManager: ActivityManager? =
        appContext.getSystemService(ActivityManager::class.java)
    private val packageManager: PackageManager = appContext.packageManager

    override fun kill(packageName: String): Boolean {
        val am = activityManager ?: return false

        if (packageName == appContext.packageName) return false
        if (isSystemOrUnknown(packageName)) return false

        return try {
            am.killBackgroundProcesses(packageName)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isSystemOrUnknown(packageName: String): Boolean = try {
        val flags = packageManager.getApplicationInfo(packageName, 0).flags
        (flags and ApplicationInfo.FLAG_SYSTEM) != 0
    } catch (_: Exception) {
        true
    }
}