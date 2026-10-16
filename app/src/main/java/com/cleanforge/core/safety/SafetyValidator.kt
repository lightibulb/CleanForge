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
        //    Android/media paths for FORENSIC_MEDIA are already validated by step 3.
        //    This step protects top-level personal folders (DCIM, Pictures, WhatsApp, etc.)
        if (PathRules.isProtectedZone(c) && item.category != CleaningCategory.THUMBNAIL) {
            val isAndroidMedia = c.size >= 2 &&
                c[0].equals("Android", ignoreCase = true) &&
                c[1].equals("media", ignoreCase = true)

            if (!isAndroidMedia) {
                if (
                    item.category == CleaningCategory.APP_CACHE ||
                    item.category == CleaningCategory.CORPSE_LEFTOVER ||
                    item.category == CleaningCategory.FORENSIC_MEDIA
                ) {
                    refuse("Cache/leftover/forensic rules do not apply inside personal folders")
                }
            }
            if (item.isPreselected) refuse("Personal data can never be pre-selected")
        }
    }

    /**
     * Very strict allowlist for FORENSIC_MEDIA.
     * This does NOT allow arbitrary Android/data or Android/media cleanup.
     * It only allows the exact app-specific forensic paths produced by the forensic scanners.
     */
    private fun isKnownForensicPath(c: List<String>): Boolean {
        // Need at least: Android / area / package / subfolder / file
        if (c.size < 5) return false
        if (!c[0].equals("Android", ignoreCase = true)) return false

        val area = c[1]
        val pkg = c[2] // exact match: the folder name must be the package's real name
        val tail = c.drop(3)
        val lowerTail = tail.map { it.lowercase() }

        // Android/media/<pkg>/...
        if (area.equals("media", ignoreCase = true)) {
            return when (pkg) {
                "com.whatsapp", "com.whatsapp.w4b" -> {
                    lowerTail.hasPrefix("whatsapp", "media", ".statuses") ||
                    lowerTail.hasPrefix("whatsapp", "media", "whatsapp video", "sent") ||
                    lowerTail.hasPrefix("whatsapp", "media", "whatsapp images", "sent") ||
                    lowerTail.hasPrefix("whatsapp", "media", "whatsapp animated gifs", "sent") ||
                    (
                        lowerTail.hasPrefix("whatsapp", "databases") &&
                        tail.last().lowercase().startsWith("msgstore-")
                    )
                }
                "org.telegram.messenger",
                "org.telegram.messenger.web",
                "org.telegram.plus" -> {
                    lowerTail.hasPrefix("telegram", "telegram video") ||
                    lowerTail.hasPrefix("telegram", "telegram audio") ||
                    lowerTail.hasPrefix("telegram", "telegram images") ||
                    lowerTail.hasPrefix("telegram", "telegram documents")
                }
                else -> false
            }
        }

        // Android/data/<pkg>/...
        if (area.equals("data", ignoreCase = true)) {
            return when (pkg) {
                "com.zhiliaoapp.musically",
                "com.zhiliaoapp.musically.go" -> {
                    lowerTail.hasPrefix("cache") ||
                    lowerTail.hasPrefix("files", ".music")
                }
                "com.twitter.android",
                "com.twitter.android.lite" -> {
                    lowerTail.hasPrefix("cache")
                }
                "com.instagram.android",
                "com.instagram.lite" -> {
                    lowerTail.hasPrefix("cache")
                }
                else -> false
            }
        }

        return false
    }

    /**
     * The list must contain the full prefix AND at least one extra component after it.
     * Example:
     *   [telegram, telegram video, file.mp4] hasPrefix("telegram", "telegram video") = true
     *   [telegram, telegram video] hasPrefix("telegram", "telegram video") = false
     */
    private fun List<String>.hasPrefix(vararg prefix: String): Boolean {
        if (size != prefix.size + 1) return false
        return prefix.indices.all { index ->
            this[index].equals(prefix[index], ignoreCase = true)
        }
    }

    private fun refuse(message: String): Nothing = throw UnsafeDeletionException(message)
}