package com.cleanforge.core.model

/** How sure the scanner is that the item is safe to delete, based on the evidence it gathered. */
enum class ConfidenceLevel { HIGH, MEDIUM, LOW, UNKNOWN }

enum class RiskLevel {
    SAFE,       // re-created automatically (app caches, thumbnails)
    ELEVATED,   // could lose something the user may want (leftover app data, installers, logs)
    DANGEROUS   // possibly irreplaceable user data (large files, duplicates in personal folders)
}

enum class CleaningCategory(val label: String, val isQuick: Boolean) {
    APP_CACHE("App caches", true),
    THUMBNAIL("Thumbnail cache", true),
    TEMP_FILE("Incomplete downloads", true),
    APK_FILE("APK installers", true),
    CORPSE_LEFTOVER("Leftovers of uninstalled apps", true),
    LARGE_FILE("Large files", false),
    DUPLICATE("Duplicate files", false),
    LOG_FILE("Old log files", false),
    EMPTY_DIRECTORY("Empty folders", false)
}

enum class Reversibility {
    TRIVIALLY_RECREATABLE,  // caches
    RE_DOWNLOADABLE,        // installers, partial downloads
    NOT_REVERSIBLE          // deleted for good: CleanForge has no recycle bin
}
