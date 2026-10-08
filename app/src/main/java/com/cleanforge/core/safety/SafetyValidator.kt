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

        // 1. Delete mode must match the category: recursive deletion is reserved for three exact patterns.
        val allowedMode = when (item.category) {
            CleaningCategory.APP_CACHE, CleaningCategory.THUMBNAIL -> DeleteMode.CONTENTS_ONLY
            CleaningCategory.CORPSE_LEFTOVER -> DeleteMode.TREE
            CleaningCategory.EMPTY_DIRECTORY -> DeleteMode.EMPTY_DIR
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
            else -> Unit
        }

        // 3. Android/ is reachable only through the patterns above (never Android/data itself, never Android/media).
        if (c[0].equals("Android", ignoreCase = true)) {
            val ok = (item.category == CleaningCategory.APP_CACHE && PathRules.isExternalAppCacheDir(c)) ||
                (item.category == CleaningCategory.CORPSE_LEFTOVER && PathRules.isExternalAppDataRoot(c))
            if (!ok) refuse("Android/ folders can only be cleaned as an app cache or an uninstalled app's folder")
        }

        // 4. Never a top-level folder or file of storage.
        if (c.size < 2) refuse("Refusing to delete a top-level storage folder")

        // 5. Personal-data zones: only individually reviewed files, never pre-selected.
        if (PathRules.isProtectedZone(c) && item.category != CleaningCategory.THUMBNAIL) {
            if (item.category == CleaningCategory.APP_CACHE || item.category == CleaningCategory.CORPSE_LEFTOVER) {
                refuse("Cache/leftover rules do not apply inside personal folders")
            }
            if (item.isPreselected) refuse("Personal data can never be pre-selected")
        }
    }

    private fun refuse(message: String): Nothing = throw UnsafeDeletionException(message)
}
