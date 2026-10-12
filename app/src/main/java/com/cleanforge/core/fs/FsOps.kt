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

/** lstat that tells "does not exist" apart from "could not be examined". */
sealed interface FsProbe {
    data class Found(val stat: FsStat) : FsProbe

    /** ENOENT / ENOTDIR: a real answer, the entry is not there. */
    data object Missing : FsProbe

    /** EACCES / EPERM: the entry may exist but this process may not look at it. */
    data object Denied : FsProbe

    /** Any other failure. */
    data object Error : FsProbe
}

/**
 * The only filesystem surface the cleaning code is allowed to use.
 * Implementations MUST NOT follow symlinks and MUST NOT recurse.
 */
interface FsOps {
    /** lstat. Null if the entry does not exist or cannot be examined. */
    fun lstat(path: String): FsStat?

    /**
     * Like [lstat] but keeps the failure reason. The default cannot tell the cases apart and reports [FsProbe.Missing];
     * implementations that can (the real one) override it.
     */
    fun probe(path: String): FsProbe = lstat(path)?.let { FsProbe.Found(it) } ?: FsProbe.Missing

    /** Child names (never paths). Null if [path] cannot be listed. */
    fun list(path: String): List<String>?

    /** unlink a file/symlink, or rmdir an EMPTY directory. Never recursive, never follows links. */
    fun remove(path: String): Boolean
}

data class WalkLimits(
    val maxDepth: Int = 32,
    val maxEntries: Int = 200_000
)

/**
 * Measured logical size (sum of st_size of regular files, NOT allocated blocks).
 * [complete] is false when a safety bound cut the walk short; [unreadable] counts directories that could not be
 * listed (their contents are missing from [bytes]). Either one means [bytes] is a lower bound, never an exact figure.
 */
data class SizeResult(val bytes: Long, val complete: Boolean, val unreadable: Int = 0) {
    /** True only if nothing was cut off and nothing was unreadable. */
    val isExact: Boolean get() = complete && unreadable == 0
}

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
    LIMIT_EXCEEDED(7),

    /** The user (or a timeout policy) stopped the operation between entries. Some entries may already be gone. */
    CANCELLED(8),

    /** Produced on the app side only: the privileged channel was not usable, so nothing is known to have happened. */
    UNAVAILABLE(9),

    /** Produced on the app side only: the call did not return in time; the outcome is unknown. */
    TIMED_OUT(10);

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
