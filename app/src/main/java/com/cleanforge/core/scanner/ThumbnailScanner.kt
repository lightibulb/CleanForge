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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * The folder NAME alone is not evidence. Flagged only if the folder is a real directory that contains
 * only small regular files (<= 2 MB each) and no sub-folders, i.e. it looks like a thumbnail cache and
 * not like someone's album. Never pre-selected.
 */
class ThumbnailScanner(
    private val fs: FsOps,
    private val root: String = PathRules.ROOT
) : Scanner {
    override val id = "thumbnails"
    override val displayName = "Thumbnail cache"
    override val requirement = Requirement.ALL_FILES_ACCESS
    override val mode = ScanMode.QUICK

    override fun scan(): Flow<List<ScanItem>> = flow {
        val dir = "$root/DCIM/.thumbnails"
        val st = fs.lstat(dir) ?: return@flow
        if (st.type != FsType.DIRECTORY) return@flow

        var files = 0
        var bytes = 0L
        var looksLikeCache = true
        val walker = StorageWalker(fs)
        for (e in walker.walk(dir, onTruncated = { looksLikeCache = false })) {
            if (e.stat.type != FsType.FILE || e.stat.size > MAX_THUMB_BYTES) {
                looksLikeCache = false
                break
            }
            files++
            bytes += e.stat.size
        }
        if (!looksLikeCache || files == 0 || bytes <= 0L) return@flow

        emit(
            listOf(
                ScanItem(
                    path = dir,
                    sizeBytes = bytes,
                    category = CleaningCategory.THUMBNAIL,
                    reasonForFlagging = "Legacy gallery thumbnail folder: $files small files (none over 2 MB), " +
                        "no sub-folders. Gallery apps rebuild thumbnails when needed.",
                    confidence = ConfidenceLevel.MEDIUM,
                    risk = RiskLevel.SAFE,
                    deleteMode = DeleteMode.CONTENTS_ONLY,
                    reversibility = Reversibility.TRIVIALLY_RECREATABLE,
                    lastModified = st.mtimeMs,
                    fingerprint = st
                )
            )
        )
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val MAX_THUMB_BYTES = 2L * 1024 * 1024
    }
}
