package com.cleanforge.core.backend

import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.fs.DeleteResult
import com.cleanforge.core.fs.FsStat
import com.cleanforge.core.fs.SizeResult

/** Where filesystem work happens: this app's own process, or the Shizuku service process. */
interface FileOperationBackend {
    val name: String

    suspend fun lstat(path: String): FsStat?
    suspend fun list(path: String): List<String>?

    /** Null if unreadable. [SizeResult.complete] is false when a safety bound cut the walk short. */
    suspend fun directorySize(path: String): SizeResult?

    suspend fun delete(path: String, mode: DeleteMode, expected: FsStat?): DeleteResult
}
