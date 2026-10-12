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
 * What Android says about ONE package name for the current user/profile. A folder under Android/data is never evidence
 * for any of these: the answer comes only from the package manager.
 */
enum class InstallState {
    /** Installed right now. */
    INSTALLED,

    /** Uninstalled but Android kept its data: it has an identity, but it is not installed. */
    UNINSTALLED_DATA_KEPT,

    /** The package manager has no record of this name for this user (authoritative "not found"). */
    NOT_KNOWN,

    /** The query failed for any other reason. NOT the same as "not installed": callers must fail closed. */
    UNKNOWN
}

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

    /**
     * The authoritative per-package answer. Which way to fail closed depends on the caller (a cache is only cleared
     * for INSTALLED, a leftover is only removed for NOT_KNOWN), so a failed query is [InstallState.UNKNOWN] and never
     * silently becomes "not installed".
     *
     * The default derives the answer from the two methods above and is meant for fakes; [AndroidPackageInventory]
     * overrides it with a single package-manager lookup.
     */
    fun installState(packageName: String): InstallState {
        val known = knownPackageNames() ?: return InstallState.UNKNOWN
        return when {
            isCurrentlyInstalled(packageName) -> InstallState.INSTALLED
            packageName in known -> InstallState.UNINSTALLED_DATA_KEPT
            else -> InstallState.NOT_KNOWN
        }
    }

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

    /**
     * One lookup WITH MATCH_UNINSTALLED_PACKAGES: found + FLAG_INSTALLED = installed, found without it = uninstalled
     * with data kept. NameNotFoundException is the platform's authoritative "no such package for this user"; every
     * other failure (remote errors while system_server restarts, security errors) is UNKNOWN, not "not installed".
     */
    @Suppress("DEPRECATION")
    override fun installState(packageName: String): InstallState = try {
        val info = pm.getPackageInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        val app: ApplicationInfo? = info.applicationInfo
        when {
            info.packageName != packageName || app == null -> InstallState.UNKNOWN
            (app.flags and ApplicationInfo.FLAG_INSTALLED) != 0 -> InstallState.INSTALLED
            else -> InstallState.UNINSTALLED_DATA_KEPT
        }
    } catch (e: PackageManager.NameNotFoundException) {
        InstallState.NOT_KNOWN
    } catch (e: Exception) {
        InstallState.UNKNOWN
    }

    @Suppress("DEPRECATION")
    override fun archiveInfo(apkPath: String): ArchiveInfo? = try {
        pm.getPackageArchiveInfo(apkPath, 0)?.let { ArchiveInfo(it.packageName, it.longVersionCode) }
    } catch (e: Exception) {
        null
    }
}
