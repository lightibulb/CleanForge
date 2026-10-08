package com.cleanforge.core.shizuku

import android.os.Process
import com.cleanforge.core.fs.AndroidFsOps
import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.fs.DeleteStatus
import com.cleanforge.core.fs.FsStat
import com.cleanforge.core.fs.FsType
import com.cleanforge.core.fs.PathRules
import com.cleanforge.core.fs.SafeDeleter
import com.cleanforge.core.fs.StorageWalker
import com.cleanforge.shizuku.IFileService
import kotlin.system.exitProcess

/**
 * Runs in the Shizuku-started process (shell UID, or root if the user chose root mode).
 * Public no-arg constructor is required by Shizuku. It trusts nothing from the caller:
 * every path is re-checked against [PathRules] and only two recursive delete shapes exist.
 */
class ShizukuFileService : IFileService.Stub() {

    private val fs = AndroidFsOps
    private val walker = StorageWalker(fs)
    private val deleter = SafeDeleter(fs)

    override fun destroy() {
        exitProcess(0)
    }

    override fun uid(): Int = Process.myUid()

    override fun lstat(path: String?): LongArray? {
        if (path == null || !readable(path)) return null
        val st = fs.lstat(path) ?: return null
        return longArrayOf(st.type.code.toLong(), st.size, st.mtimeMs, st.dev, st.ino)
    }

    override fun list(path: String?): Array<String>? {
        if (path == null || !readable(path)) return null
        val st = fs.lstat(path)
        if (st == null || st.type != FsType.DIRECTORY) return null
        val names = fs.list(path) ?: return null
        return names.filter { StorageWalker.isPlainName(it) }.take(MAX_NAMES).toTypedArray()
    }

    override fun directorySize(path: String?): LongArray? {
        if (path == null || !readable(path)) return null
        val r = walker.sizeOf(path) ?: return null
        return longArrayOf(r.bytes, if (r.complete) 1L else 0L)
    }

    override fun delete(path: String?, mode: Int, dev: Long, ino: Long, hasIdentity: Boolean): LongArray {
        val m = DeleteMode.fromCode(mode)
        if (path == null || m == null || !hasIdentity || !PathRules.isServiceDeletable(path, m)) {
            return longArrayOf(DeleteStatus.REFUSED.code.toLong(), 0L, 0L)
        }
        // Only directories are ever deleted here, so identity = (type, dev, ino).
        val expected = FsStat(FsType.DIRECTORY, 0L, 0L, dev, ino)
        val r = deleter.delete(path, m, expected)
        return longArrayOf(r.status.code.toLong(), r.entriesRemoved.toLong(), r.bytesFreed)
    }

    private fun readable(path: String): Boolean =
        PathRules.isServiceReadable(path) || path == "${PathRules.ROOT}/Android/data" || path == "${PathRules.ROOT}/Android/obb"

    private companion object {
        const val MAX_NAMES = 20_000
    }
}
