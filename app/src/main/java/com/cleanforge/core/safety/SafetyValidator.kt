package com.cleanforge.core.safety

import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.fs.PathException
import com.cleanforge.core.fs.PathRules
import com.cleanforge.core.model.CleaningCategory
import com.cleanforge.core.model.ScanItem
import javax.inject.Inject
import javax.inject.Singleton

class UnsafeDeletionException(message: String) : Exception(message)

/**
 * Central gate for every destructive operation (called at plan time AND again right before deletion).
 * Purely lexical + policy; the live filesystem checks (symlink, identity, mount) live in SafeDeleter/CleaningEngine.
 */
@Singleton
class SafetyValidator @Inject constructor() {

    /** Null if the item may be deleted, otherwise the human-readable reason it may not. */
    fun rejectionReason(item: ScanItem): String? = try {
        validateDeletion(item.path, item)
        null
    } catch (e: UnsafeDeletionException) {
        e.message ?: "Rejected by safety policy"
    }

    @Throws(UnsafeDeletionException::class)
    fun validateDeletion(path: String, item: ScanItem) {
        if (path != item.path) refuse("Path does not match the scanned item")
        if (item.sizeBytes < 0) refuse("Invalid size")

        val c = try {
            PathRules.componentsBelowRoot(path)
        } catch (e: PathException) {
            refuse(e.message ?: "Invalid path")
        }

        // 1. Delete mode must match the category.
        val allowedMode = when (item.category) {
            CleaningCategory.APP_CACHE, CleaningCategory.THUMBNAIL -> DeleteMode.CONTENTS_ONLY
            CleaningCategory.CORPSE_LEFTOVER -> DeleteMode.TREE
            CleaningCategory.EMPTY_DIRECTORY -> DeleteMode.EMPTY_DIR
            CleaningCategory.FORENSIC_MEDIA -> DeleteMode.FILE
            else -> DeleteMode.FILE
        }

        if (item.deleteMode != allowedMode) {
            refuse("Delete mode ${item.deleteMode} is not allowed for ${item.category.label}")
        }

        // 2. Exact path patterns for recursive categories.
        when (item.category) {
            CleaningCategory.APP_CACHE -> {
                if (!PathRules.isExternalAppCacheDir(c)) refuse("Not an app's external cache folder")
                if (item.owningPackage != c[2]) refuse("Owning package does not match the folder")
            }

            CleaningCategory.CORPSE_LEFTOVER -> {
                if (!PathRules.isExternalAppDataRoot(c)) refuse("Not an app's external data folder")
                if (item.owningPackage != c[2]) refuse("Owning package does not match the folder")
            }

            CleaningCategory.THUMBNAIL -> {
                if (!PathRules.isThumbnailDir(c)) refuse("Not the known thumbnail folder")
            }

            CleaningCategory.FORENSIC_MEDIA -> {
                if (!isKnownForensicPath(c)) refuse("Not a known app-specific forensic media path")
                if (item.owningPackage == null) refuse("Forensic media items must declare their owning package")
                if (item.owningPackage != c[2]) refuse("Owning package does not match the folder")
            }

            else -> Unit
        }

        // 3. Android/ is reachable only through approved patterns.
        if (c[0].equals("Android", ignoreCase = true)) {
            val ok =
                (item.category == CleaningCategory.APP_CACHE && PathRules.isExternalAppCacheDir(c)) ||
                (item.category == CleaningCategory.CORPSE_LEFTOVER && PathRules.isExternalAppDataRoot(c)) ||
                (item.category == CleaningCategory.FORENSIC_MEDIA && isKnownForensicPath(c))

            if (!ok) {
                refuse("Android/ folders can only be cleaned as an app cache, an uninstalled app's folder, or a known forensic media path")
            }
        }

        // 4. Never a top-level folder or file of storage.
        if (c.size < 2) refuse("Refusing to delete a top-level storage folder")

        // 5. Personal-data zones: only individually reviewed files, never pre-selected.
        // Android/media counts as a protected zone in PathRules, but the exact per-package files of the forensic
        // allowlist live there; they were already validated in step 2 and are single files, never pre-selected.
        // Every other protected zone (DCIM, Pictures, Download, ...) still refuses forensic items.
        val allowlistedForensicInAndroidMedia = item.category == CleaningCategory.FORENSIC_MEDIA &&
            c[0].equals("Android", ignoreCase = true) && c[1].equals("media", ignoreCase = true) &&
            isKnownForensicPath(c)
        if (PathRules.isProtectedZone(c) && item.category != CleaningCategory.THUMBNAIL && !allowlistedForensicInAndroidMedia) {
            if (
                item.category == CleaningCategory.APP_CACHE ||
                item.category == CleaningCategory.CORPSE_LEFTOVER ||
                item.category == CleaningCategory.FORENSIC_MEDIA
            ) {
                refuse("Cache/leftover/forensic rules do not apply inside personal folders")
            }

            if (item.isPreselected) refuse("Personal data can never be pre-selected")
        }
    }

    /**
     * Very strict allowlist for FORENSIC_MEDIA.
     *
     * This does NOT allow arbitrary Android/data or Android/media cleanup. It only allows a single FILE that is a
     * DIRECT child of one exact app-specific folder (the scanners only ever list one level and propose files).
     * Matching is case-insensitive because shared storage is.
     */
    private fun isKnownForensicPath(c: List<String>): Boolean {
        // Need at least: Android / area / package / subfolder / file
        if (c.size < 5) return false
        if (!c[0].equals("Android", ignoreCase = true)) return false

        val area = c[1]
        val pkg = c[2].lowercase()
        val tail = c.drop(3)

        // Android/media/<pkg>/...
        if (area.equals("media", ignoreCase = true)) {
            return when (pkg) {
                "com.whatsapp", "com.whatsapp.w4b" ->
                    tail.isDirectChildOf("WhatsApp", "Media", ".Statuses") ||
                        tail.isDirectChildOf("WhatsApp", "Media", "WhatsApp Video", "Sent") ||
                        tail.isDirectChildOf("WhatsApp", "Media", "WhatsApp Images", "Sent") ||
                        tail.isDirectChildOf("WhatsApp", "Media", "WhatsApp Animated Gifs", "Sent") ||
                        (tail.isDirectChildOf("WhatsApp", "Databases") &&
                            tail.last().startsWith("msgstore-", ignoreCase = true))

                "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.plus" ->
                    tail.isDirectChildOf("Telegram", "Telegram Video") ||
                        tail.isDirectChildOf("Telegram", "Telegram Audio") ||
                        tail.isDirectChildOf("Telegram", "Telegram Images") ||
                        tail.isDirectChildOf("Telegram", "Telegram Documents")

                else -> false
            }
        }

        // Android/data/<pkg>/...
        if (area.equals("data", ignoreCase = true)) {
            return when (pkg) {
                "com.zhiliaoapp.musically", "com.zhiliaoapp.musically.go" ->
                    tail.isDirectChildOf("cache") || tail.isDirectChildOf("files", ".music")

                "com.twitter.android", "com.twitter.android.lite",
                "com.instagram.android", "com.instagram.lite" ->
                    tail.isDirectChildOf("cache")

                else -> false
            }
        }

        return false
    }

    /**
     * True only for `dir... / oneEntry`: the folder path matches [dir] exactly (case-insensitive) and exactly one
     * component follows. The folder itself and anything nested deeper are refused.
     */
    private fun List<String>.isDirectChildOf(vararg dir: String): Boolean =
        size == dir.size + 1 && dir.indices.all { this[it].equals(dir[it], ignoreCase = true) }

    private fun refuse(message: String): Nothing = throw UnsafeDeletionException(message)
}