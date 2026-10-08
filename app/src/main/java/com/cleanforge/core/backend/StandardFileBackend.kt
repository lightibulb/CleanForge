package com.cleanforge.core.backend

import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.fs.DeleteResult
import com.cleanforge.core.fs.FsOps
import com.cleanforge.core.fs.FsStat
import com.cleanforge.core.fs.SafeDeleter
import com.cleanforge.core.fs.SizeResult
import com.cleanforge.core.fs.StorageWalker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Runs in the app process. Reaches shared storage with All-files access; NOT Android/data or Android/obb. */
class StandardFileBackend(private val fs: FsOps) : FileOperationBackend {
    override val name: String = "App (All-files access)"

    private val walker = StorageWalker(fs)
    private val deleter = SafeDeleter(fs)

    override suspend fun lstat(path: String): FsStat? = withContext(Dispatchers.IO) { fs.lstat(path) }

    override suspend fun list(path: String): List<String>? = withContext(Dispatchers.IO) { fs.list(path) }

    override suspend fun directorySize(path: String): SizeResult? =
        withContext(Dispatchers.IO) { walker.sizeOf(path) }

    override suspend fun delete(path: String, mode: DeleteMode, expected: FsStat?): DeleteResult =
        withContext(Dispatchers.IO) { deleter.delete(path, mode, expected) }
}
