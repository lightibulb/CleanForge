package com.cleanforge.core.fs

class PathException(message: String) : Exception(message)

/**
 * Pure, lexical path policy. Shared by the app-side SafetyValidator and the Shizuku service guard.
 *
 * Shared storage on Android is CASE-INSENSITIVE (FUSE/sdcardfs), so every structural comparison
 * (DCIM, Android, data, cache ...) is done case-insensitively.
 */
object PathRules {
    /** The only accepted spelling of the primary shared-storage root. Aliases (/sdcard, /storage/self/primary) are rejected. */
    const val ROOT = "/storage/emulated/0"
    const val MAX_PATH = 4096

    private val PACKAGE_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    private val PROTECTED_TOP_LEVEL = setOf(
        "dcim", "pictures", "movies", "music", "documents", "download", "downloads",
        "recordings", "audiobooks", "podcasts", "ringtones", "alarms", "notifications",
        "whatsapp", "telegram"
    )

    fun isValidPackageName(name: String): Boolean = name.length <= 255 && PACKAGE_REGEX.matches(name)

    /**
     * Components of [path] below [ROOT]. Throws [PathException] for anything not already canonical:
     * relative, empty/".."/"." segments, trailing slash, NUL/control characters, over-long, or outside ROOT.
     */
    fun componentsBelowRoot(path: String): List<String> {
        if (path.isEmpty()) throw PathException("Empty path")
        if (path.length > MAX_PATH) throw PathException("Path is too long")
        for (ch in path) {
            if (ch.code < 0x20 || ch.code == 0x7f) throw PathException("Control character in path")
        }
        if (!path.startsWith("/")) throw PathException("Path is not absolute")
        if (path.endsWith("/")) throw PathException("Path has a trailing slash")
        val parts = path.substring(1).split('/')
        for (p in parts) {
            if (p.isEmpty()) throw PathException("Empty path segment")
            if (p == "." || p == "..") throw PathException("Relative path segment '$p'")
        }
        val rootParts = ROOT.substring(1).split('/')
        if (parts.size <= rootParts.size || parts.subList(0, rootParts.size) != rootParts) {
            throw PathException("Path is not below the canonical storage root $ROOT")
        }
        return parts.subList(rootParts.size, parts.size)
    }

    private fun String.eq(other: String) = this.equals(other, ignoreCase = true)

    /** Android/data/<pkg>/cache */
    fun isExternalAppCacheDir(c: List<String>): Boolean =
        c.size == 4 && c[0].eq("Android") && c[1].eq("data") && isValidPackageName(c[2]) && c[3].eq("cache")

    /** Android/data/<pkg> or Android/obb/<pkg> */
    fun isExternalAppDataRoot(c: List<String>): Boolean =
        c.size == 3 && c[0].eq("Android") && (c[1].eq("data") || c[1].eq("obb")) && isValidPackageName(c[2])

    /** DCIM/.thumbnails */
    fun isThumbnailDir(c: List<String>): Boolean =
        c.size == 2 && c[0].eq("DCIM") && c[1].eq(".thumbnails")

    /** Personal-data zones: only ever touched through explicit review of individual files. */
    fun isProtectedZone(c: List<String>): Boolean {
        if (c.isEmpty()) return false
        if (c[0].lowercase() in PROTECTED_TOP_LEVEL) return true
        return c[0].eq("Android") && c.size > 1 && c[1].eq("media")
    }

    // ---- Shizuku service guard (runs in the privileged process) ----

    /** Read access: Android/data, Android/obb and anything below them. */
    fun isServiceReadable(path: String): Boolean = try {
        val c = componentsBelowRoot(path)
        c.size >= 2 && c[0].eq("Android") && (c[1].eq("data") || c[1].eq("obb"))
    } catch (e: PathException) {
        false
    }

    /** Delete access: exactly the two recursive patterns, nothing else. */
    fun isServiceDeletable(path: String, mode: DeleteMode): Boolean = try {
        val c = componentsBelowRoot(path)
        when (mode) {
            DeleteMode.TREE -> isExternalAppDataRoot(c)
            DeleteMode.CONTENTS_ONLY -> isExternalAppCacheDir(c)
            else -> false
        }
    } catch (e: PathException) {
        false
    }
}
