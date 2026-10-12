package com.cleanforge.core.backend

import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.fs.DeleteResult
import com.cleanforge.core.fs.FsStat
import com.cleanforge.core.fs.SizeResult

/** Child names of a directory. [truncated] means the listing was cut at a safety bound: there are more entries. */
data class DirListing(val names: List<String>, val truncated: Boolean = false)

/**
 * The read side of a backend. Every call says WHY it has no value ([FsResult]); none of the failure cases may be
 * treated as "nothing there".
 */
interface FileReadBackend {
    val name: String

    suspend fun lstat(path: String): FsResult<FsStat>
    suspend fun list(path: String): FsResult<DirListing>

    /** [SizeResult.isExact] is false when a safety bound cut the walk short or sub-folders were unreadable. */
    suspend fun directorySize(path: String): FsResult<SizeResult>
}

/**
 * Where ordinary (non-privileged) filesystem work happens: this app's own process. Privileged work does NOT go
 * through this interface; it goes through [com.cleanforge.core.privileged.PrivilegedExecutor].
 */
interface FileOperationBackend : FileReadBackend {
    suspend fun delete(path: String, mode: DeleteMode, expected: FsStat?): DeleteResult
}
