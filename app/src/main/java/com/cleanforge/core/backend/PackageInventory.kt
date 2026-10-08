package com.cleanforge.core.backend

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Fail-closed guard, NOT proof that a package query succeeded. A real device always has far more than this
 * many packages, so a smaller list almost certainly means the query failed or was filtered, and callers then
 * produce no findings and skip deletions. An explicit failure is reported as `null` by
 * [PackageInventory.knownPackageNames]; this count is only a second line of defence on top of that.
 */
const val MIN_PLAUSIBLE_PACKAGES = 20

data class ArchiveInfo(val packageName: String, val versionCode: Long)

/**
 * What the OS knows about apps, for the CURRENT Android user/profile only (other users and work profiles are
 * not enumerated). Never inferred from folder names.
 *
 * Two different questions, two different methods. Never use one where the other is meant:
 *  - [knownPackageNames]: does Android still have an identity for this name (installed OR uninstalled with data kept)?
 *  - [isCurrentlyInstalled]: is the app installed right now?
 */
interface PackageInventory {
    /**
     * Every package the OS still knows about for the current user/profile, INCLUDING ones uninstalled with
     * their data kept. It is NOT evidence that an app is installed. Its job is to stop data-kept packages from
     * being called "leftovers". Null when the query failed (callers must then do nothing, never assume
     * "nothing is installed").
     */
    fun knownPackageNames(): Set<String>?

    /**
     * True only if [packageName] is installed for the current user/profile right now. A package that is merely
     * known (uninstalled with data kept) is NOT installed. Fails closed: any error answers false.
     */
    fun isCurrentlyInstalled(packageName: String): Boolean

    fun installedVersionCode(packageName: String): Long?

    /** Reads an APK with the platform parser. Null if it is not a readable APK. */
    fun archiveInfo(apkPath: String): ArchiveInfo?
}

class AndroidPackageInventory(context: Context) : PackageInventory {
    private val pm: PackageManager = context.applicationContext.packageManager

    @Suppress("DEPRECATION")
    override fun knownPackageNames(): Set<String>? = try {
        pm.getInstalledPackages(PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .map { it.packageName }
            .toSet()
    } catch (e: Exception) {
        null
    }

    /**
     * Deliberately WITHOUT MATCH_UNINSTALLED_PACKAGES: without that flag the platform does not return packages
     * that were uninstalled with their data kept. As a second, independent check the application's
     * FLAG_INSTALLED bit must also be set (it is cleared for data-kept packages).
     */
    @Suppress("DEPRECATION")
    override fun isCurrentlyInstalled(packageName: String): Boolean = try {
        val info = pm.getPackageInfo(packageName, 0)
        val app: ApplicationInfo? = info.applicationInfo
        info.packageName == packageName && app != null && (app.flags and ApplicationInfo.FLAG_INSTALLED) != 0
    } catch (e: Exception) {
        false
    }

    @Suppress("DEPRECATION")
    override fun installedVersionCode(packageName: String): Long? = try {
        pm.getPackageInfo(packageName, 0).longVersionCode
    } catch (e: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    override fun archiveInfo(apkPath: String): ArchiveInfo? = try {
        pm.getPackageArchiveInfo(apkPath, 0)?.let { ArchiveInfo(it.packageName, it.longVersionCode) }
    } catch (e: Exception) {
        null
    }
}
