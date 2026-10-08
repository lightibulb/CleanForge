package com.cleanforge.core.scanner

import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.fs.FsOps
import com.cleanforge.core.fs.FsType
import com.cleanforge.core.fs.PathRules
import com.cleanforge.core.fs.StorageWalker
import com.cleanforge.core.model.CleaningCategory
import com.cleanforge.core.model.ConfidenceLevel
import com.cleanforge.core.model.Reversibility
import com.cleanforge.core.model.RiskLevel
import com.cleanforge.core.model.ScanItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Folders that are verifiably empty RIGHT NOW (zero entries, hidden files such as .nomedia count as entries).
 * Only NESTED folders are listed (never Download/DCIM/Pictures/... themselves) and hidden folders are skipped.
 * Frees 0 bytes: tidy-up only. Deletion uses rmdir, which the OS refuses if anything appeared meanwhile.
 */
class EmptyDirectoryScanner(
    private val fs: FsOps,
    private val root: String = PathRules.ROOT
) : Scanner {
    override val id = "empty_dirs"
    override val displayName = "Empty folders"
    override val requirement = Requirement.ALL_FILES_ACCESS
    override val mode = ScanMode.ON_DEMAND

    override fun scan(): Flow<List<ScanItem>> = flow {
        val batch = ArrayList<ScanItem>()
        var n = 0
        for (dir in listOf("Download", "Documents", "Pictures", "Movies")) {
            val walker = StorageWalker(fs)
            for (e in walker.walk("$root/$dir", skipDir = { it.substringAfterLast('/').startsWith(".") })) {
                if (++n % 256 == 0) currentCoroutineContext().ensureActive()
                if (e.stat.type != FsType.DIRECTORY || e.name.startsWith(".")) continue
                val children = fs.list(e.path) ?: continue
                if (children.isNotEmpty()) continue
                batch += ScanItem(
                    path = e.path,
                    sizeBytes = 0L,
                    category = CleaningCategory.EMPTY_DIRECTORY,
                    reasonForFlagging = "Contains nothing at all. Removing it frees no space; apps recreate folders they need.",
                    confidence = ConfidenceLevel.MEDIUM,
                    risk = RiskLevel.SAFE,
                    deleteMode = DeleteMode.EMPTY_DIR,
                    reversibility = Reversibility.TRIVIALLY_RECREATABLE,
                    lastModified = e.stat.mtimeMs,
                    fingerprint = e.stat
                )
                if (batch.size >= BATCH_SIZE) {
                    emit(batch.toList())
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) emit(batch.toList())
    }.flowOn(Dispatchers.IO)
}
