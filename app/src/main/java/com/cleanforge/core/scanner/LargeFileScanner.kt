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
import java.util.PriorityQueue

/**
 * Finds big files so YOU can decide. Large does not mean junk: confidence UNKNOWN, risk DANGEROUS,
 * never pre-selected. Keeps only the [topN] largest in a bounded heap, so memory stays flat.
 */
class LargeFileScanner(
    private val fs: FsOps,
    private val root: String = PathRules.ROOT,
    private val thresholdBytes: Long = 100L * 1024 * 1024,
    private val topN: Int = 200
) : Scanner {
    override val id = "large_files"
    override val displayName = "Large files"
    override val requirement = Requirement.ALL_FILES_ACCESS
    override val mode = ScanMode.ON_DEMAND

    override fun scan(): Flow<List<ScanItem>> = flow {
        val heap = PriorityQueue<ScanItem>(compareBy<ScanItem> { it.sizeBytes })
        var n = 0
        for (dir in ROOTS) {
            for (e in StorageWalker(fs).walk("$root/$dir")) {
                if (++n % 256 == 0) currentCoroutineContext().ensureActive()
                if (e.stat.type != FsType.FILE || e.stat.size < thresholdBytes) continue
                heap.add(
                    ScanItem(
                        path = e.path,
                        sizeBytes = e.stat.size,
                        category = CleaningCategory.LARGE_FILE,
                        reasonForFlagging = "Large file. Size alone does not mean it is unneeded; " +
                            "open it and decide for yourself.",
                        confidence = ConfidenceLevel.UNKNOWN,
                        risk = RiskLevel.DANGEROUS,
                        deleteMode = DeleteMode.FILE,
                        reversibility = Reversibility.NOT_REVERSIBLE,
                        lastModified = e.stat.mtimeMs,
                        fingerprint = e.stat
                    )
                )
                if (heap.size > topN) heap.poll()
            }
        }
        if (heap.isNotEmpty()) emit(heap.sortedByDescending { it.sizeBytes })
    }.flowOn(Dispatchers.IO)

    companion object {
        val ROOTS = listOf("Download", "DCIM", "Pictures", "Movies", "Documents", "Music")
    }
}
