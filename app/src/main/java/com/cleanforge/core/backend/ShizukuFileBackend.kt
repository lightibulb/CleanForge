package com.cleanforge.core.backend

import android.os.RemoteException
import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.fs.DeleteResult
import com.cleanforge.core.fs.DeleteStatus
import com.cleanforge.core.fs.FsStat
import com.cleanforge.core.fs.FsType
import com.cleanforge.core.fs.SizeResult
import com.cleanforge.core.shizuku.ShizukuFileServiceConnector
import com.cleanforge.shizuku.IFileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Filesystem access through the Shizuku user service (shell UID). Needed ONLY for Android/data and
 * Android/obb on Android 11+. If Shizuku is unavailable every call degrades to "nothing / failed";
 * it never falls back to guessing or to a shell.
 */
class ShizukuFileBackend(private val connector: ShizukuFileServiceConnector) : FileOperationBackend {
    override val name: String = "Shizuku service"

    private suspend fun <T> call(default: T, block: (IFileService) -> T): T = withContext(Dispatchers.IO) {
        val svc = connector.get() ?: return@withContext default
        try {
            block(svc)
        } catch (e: RemoteException) {
            connector.invalidate()
            default
        } catch (e: RuntimeException) {
            connector.invalidate()
            default
        }
    }

    override suspend fun lstat(path: String): FsStat? = call<FsStat?>(null) { s ->
        val a = s.lstat(path)
        if (a == null || a.size < 5) null
        else FsStat(FsType.fromCode(a[0].toInt()), a[1], a[2], a[3], a[4])
    }

    override suspend fun list(path: String): List<String>? = call<List<String>?>(null) { s ->
        s.list(path)?.toList()
    }

    override suspend fun directorySize(path: String): SizeResult? = call<SizeResult?>(null) { s ->
        val a = s.directorySize(path)
        if (a == null || a.size < 2) null else SizeResult(a[0], a[1] == 1L)
    }

    override suspend fun delete(path: String, mode: DeleteMode, expected: FsStat?): DeleteResult {
        val unavailable = DeleteResult(DeleteStatus.FAILED, detail = "Shizuku service unavailable")
        return call(unavailable) { s ->
            val a = s.delete(path, mode.code, expected?.dev ?: 0L, expected?.ino ?: 0L, expected != null)
            if (a == null || a.size < 3) unavailable
            else DeleteResult(DeleteStatus.fromCode(a[0].toInt()), a[1].toInt(), a[2])
        }
    }
}
