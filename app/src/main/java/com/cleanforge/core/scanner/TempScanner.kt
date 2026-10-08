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
 * Abandoned partial downloads in Download/. Evidence = a browser/downloader partial-file extension
 * AND not modified for [minAgeDays] days. A bare ".tmp"/".bak" is NOT enough and ".bak" is never flagged
 * (backups are user data). DCIM/Pictures are never scanned. Never pre-selected.
 */
class TempScanner(
    private val fs: FsOps,
    private val root: String = PathRules.ROOT,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val minAgeDays: Int = 7
) : Scanner {
    override val id = "partial_downloads"
    override val displayName = "Incomplete downloads"
    override val requirement = Requirement.ALL_FILES_ACCESS
    override val mode = ScanMode.QUICK

    override fun scan(): Flow<List<ScanItem>> = flow {
        val batch = ArrayList<ScanItem>()
        var n = 0
        for (e in StorageWalker(fs).walk("$root/Download")) {
            if (++n % 256 == 0) currentCoroutineContext().ensureActive()
            if (e.stat.type != FsType.FILE || e.stat.size <= 0L) continue
            val ext = e.name.substringAfterLast('.', "").lowercase()
            val strong = ext in STRONG_PARTIAL_EXT
            if (!strong && ext !in WEAK_PARTIAL_EXT) continue
            val ageDays = (nowMs() - e.stat.mtimeMs) / DAY_MS
            if (ageDays < minAgeDays) continue
            batch += ScanItem(
                path = e.path,
                sizeBytes = e.stat.size,
                category = CleaningCategory.TEMP_FILE,
                reasonForFlagging = (if (strong) "Partial-download file (.$ext)" else "Temporary-style file (.$ext)") +
                    " in Downloads, untouched for $ageDays days. Contents were not inspected.",
                confidence = if (strong) ConfidenceLevel.MEDIUM else ConfidenceLevel.LOW,
                risk = RiskLevel.ELEVATED,
                deleteMode = DeleteMode.FILE,
                reversibility = Reversibility.RE_DOWNLOADABLE,
                lastModified = e.stat.mtimeMs,
                fingerprint = e.stat
            )
            if (batch.size >= BATCH_SIZE) {
                emit(batch.toList())
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) emit(batch.toList())
    }.flowOn(Dispatchers.IO)

    companion object {
        val STRONG_PARTIAL_EXT = setOf("crdownload", "part", "partial")
        val WEAK_PARTIAL_EXT = setOf("tmp", "download")
    }
}
