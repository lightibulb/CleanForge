package com.cleanforge.core.shizuku

import android.os.Process
import com.cleanforge.BuildConfig
import com.cleanforge.core.fs.AndroidFsOps
import com.cleanforge.core.privileged.FileServiceCore
import com.cleanforge.core.privileged.PrivilegedProtocol
import com.cleanforge.core.privileged.ReplyStatus
import com.cleanforge.core.privileged.WireCodec
import com.cleanforge.shizuku.IFileService
import kotlin.system.exitProcess

/**
 * Runs in the Shizuku-started process (shell UID, or root if the user chose root mode).
 * Public no-arg constructor is required by Shizuku. This class is only a Binder adapter: all logic, and all
 * validation, is in [FileServiceCore], which trusts nothing from the caller (path rules, symlink-free ancestors,
 * exactly two recursive delete shapes, installed-apps guard).
 */
class ShizukuFileService : IFileService.Stub() {

    private val core = FileServiceCore(AndroidFsOps, BuildConfig.APPLICATION_ID)

    override fun destroy() {
        exitProcess(0)
    }

    override fun uid(): Int = Process.myUid()

    override fun apiVersion(): Int = PrivilegedProtocol.API_VERSION

    override fun statEx(path: String?): LongArray = WireCodec.encodeStat(core.stat(path))

    override fun listEx(path: String?): Array<String> = WireCodec.encodeList(core.list(path))

    override fun sizeEx(path: String?): LongArray = WireCodec.encodeSize(core.size(path))

    override fun deleteEx(path: String?, mode: Int, dev: Long, ino: Long, hasIdentity: Boolean): Array<String> =
        WireCodec.encodeDelete(core.delete(path, mode, dev, ino, hasIdentity))

    override fun cancelCurrent() = core.cancelCurrent()

    override fun setProtectedPackages(packages: Array<String>?): Int = core.setProtectedPackages(packages)

    // ---- original methods, kept so an older app build still works; they cannot say WHY there is no value ----

    override fun lstat(path: String?): LongArray? {
        val r = core.stat(path)
        val st = r.stat
        if (r.status != ReplyStatus.OK || st == null) return null
        return longArrayOf(st.type.code.toLong(), st.size, st.mtimeMs, st.dev, st.ino)
    }

    override fun list(path: String?): Array<String>? {
        val r = core.list(path)
        return if (r.status == ReplyStatus.OK) r.names.toTypedArray() else null
    }

    override fun directorySize(path: String?): LongArray? {
        val r = core.size(path)
        val size = r.size
        if (r.status != ReplyStatus.OK || size == null) return null
        return longArrayOf(size.bytes, if (size.complete) 1L else 0L)
    }

    override fun delete(path: String?, mode: Int, dev: Long, ino: Long, hasIdentity: Boolean): LongArray {
        val r = core.delete(path, mode, dev, ino, hasIdentity)
        return longArrayOf(r.status.code.toLong(), r.entriesRemoved.toLong(), r.bytesFreed)
    }
}
