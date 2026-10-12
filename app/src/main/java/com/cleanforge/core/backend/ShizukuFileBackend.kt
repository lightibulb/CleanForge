package com.cleanforge.core.backend

import com.cleanforge.core.fs.FsStat
import com.cleanforge.core.fs.SizeResult
import com.cleanforge.core.privileged.PrivilegedExecutor

/**
 * READ access to Android/data and Android/obb through the privileged executor. Needed ONLY there on Android 11+.
 * Deliberately has no delete: privileged deletions are described as maintenance requests and run by
 * [PrivilegedExecutor.execute], never by a path-and-mode call that could aim at anything.
 * If Shizuku is unavailable every call says so ([FsResult.Unavailable]); it never falls back to guessing or a shell.
 */
class ShizukuFileBackend(private val executor: PrivilegedExecutor) : FileReadBackend {
    override val name: String = "Shizuku service"

    override suspend fun lstat(path: String): FsResult<FsStat> = executor.stat(path)
    override suspend fun list(path: String): FsResult<DirListing> = executor.list(path)
    override suspend fun directorySize(path: String): FsResult<SizeResult> = executor.measure(path)
}
