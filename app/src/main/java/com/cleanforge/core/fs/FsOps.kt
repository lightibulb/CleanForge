package com.cleanforge.core.fs

enum class FsType(val code: Int) {
    FILE(0), DIRECTORY(1), SYMLINK(2), OTHER(3);

    companion object {
        fun fromCode(code: Int): FsType = entries.firstOrNull { it.code == code } ?: OTHER
    }
}

/**
 * Snapshot of one filesystem object taken WITHOUT following symlinks (lstat).
 * Also used as the "fingerprint" recorded at scan time and compared again right before deletion.
 */
data class FsStat(
    val type: FsType,
    val size: Long,
    val mtimeMs: Long,
    val dev: Long,
    val ino: Long
) {
    /** True if [other] still describes the same object we saw before. */
    fun sameObjectAs(other: FsStat): Boolean {
        if (type != other.type || dev != other.dev || ino != other.ino) return false
        // Directory contents legitimately change (caches); files must be byte-for-byte unchanged by size+mtime.
        return type != FsType.FILE || (size == other.size && mtimeMs == other.mtimeMs)
    }
}

/**
 * The only filesystem surface the cleaning code is allowed to use.
 * Implementations MUST NOT follow symlinks and MUST NOT recurse.
 */
interface FsOps {
    /** lstat. Null if the entry does not exist or cannot be examined. */
    fun lstat(path: String): FsStat?

    /** Child names (never paths). Null if [path] cannot be listed. */
    fun list(path: String): List<String>?

    /** unlink a file/symlink, or rmdir an EMPTY directory. Never recursive, never follows links. */
    fun remove(path: String): Boolean
}

data class WalkLimits(
    val maxDepth: Int = 32,
    val maxEntries: Int = 200_000
)

data class SizeResult(val bytes: Long, val complete: Boolean)

enum class DeleteMode(val code: Int) {
    /** One regular file. */
    FILE(0),

    /** One directory, only if it is already empty. */
    EMPTY_DIR(1),

    /** Everything inside a directory; the directory itself stays. */
    CONTENTS_ONLY(2),

    /** A directory and everything inside it. */
    TREE(3);

    companion object {
        fun fromCode(code: Int): DeleteMode? = entries.firstOrNull { it.code == code }
    }
}

enum class DeleteStatus(val code: Int) {
    OK(0),
    MISSING(1),
    REFUSED(2),
    IDENTITY_CHANGED(3),
    WRONG_TYPE(4),
    FAILED(5),
    PARTIAL(6),
    LIMIT_EXCEEDED(7);

    companion object {
        fun fromCode(code: Int): DeleteStatus = entries.firstOrNull { it.code == code } ?: FAILED
    }
}

data class DeleteResult(
    val status: DeleteStatus,
    val entriesRemoved: Int = 0,
    val bytesFreed: Long = 0L,
    val detail: String = ""
)
