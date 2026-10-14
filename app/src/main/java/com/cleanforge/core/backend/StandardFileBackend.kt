package com.cleanforge.core.backend

import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.fs.DeleteResult
import com.cleanforge.core.fs.FsOps
import com.cleanforge.core.fs.FsProbe
import com.cleanforge.core.fs.FsStat
import com.cleanforge.core.fs.FsType
import com.cleanforge.core.fs.SafeDeleter
import com.cleanforge.core.fs.SizeResult
import com.cleanforge.core.fs.StorageWalker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** Runs in the app process. Reaches shared storage with All-files access; NOT Android/data or Android/obb. */
class StandardFileBackend(private val fs: FsOps) : FileOperationBackend {
    override val name: String = "App (All-files access)"

    private val walker = StorageWalker(fs)

    override suspend fun lstat(path: String): FsResult<FsStat> = withContext(Dispatchers.IO) {
        when (val p = fs.probe(path)) {
            is FsProbe.Found -> FsResult.Ok(p.stat)
            FsProbe.Missing -> FsResult.NotFound
            FsProbe.Denied -> FsResult.Denied("The system refused access")
            FsProbe.Error -> FsResult.Failed("Could not examine the path")
        }
    }

    override suspend fun list(path: String): FsResult<DirListing> = withContext(Dispatchers.IO) {
        when (val p = fs.probe(path)) {
            is FsProbe.Found -> {
                if (p.stat.type != FsType.DIRECTORY) {
                    FsResult.Failed("Not a folder")
                } else {
                    val names = fs.list(path)
                    if (names == null) FsResult.Denied("The system refused to list this folder") else FsResult.Ok(DirListing(names))
                }
            }
            FsProbe.Missing -> FsResult.NotFound
            FsProbe.Denied -> FsResult.Denied("The system refused access")
            FsProbe.Error -> FsResult.Failed("Could not examine the path")
        }
    }

    override suspend fun directorySize(path: String): FsResult<SizeResult> = withContext(Dispatchers.IO) {
        when (val p = fs.probe(path)) {
            is FsProbe.Found -> {
                val size = walker.sizeOf(path)
                if (size == null) FsResult.Failed("Not a file or folder") else FsResult.Ok(size)
            }
            FsProbe.Missing -> FsResult.NotFound
            FsProbe.Denied -> FsResult.Denied("The system refused access")
            FsProbe.Error -> FsResult.Failed("Could not examine the path")
        }
    }

    override suspend fun delete(path: String, mode: DeleteMode, expected: FsStat?): DeleteResult =
        withContext(Dispatchers.IO) {
            // Cancelling the caller stops a big folder between entries instead of after the whole thing.
            val job = currentCoroutineContext()[Job]
            SafeDeleter(fs, isCancelled = { job?.isActive == false }).delete(path, mode, expected)
        }
}
