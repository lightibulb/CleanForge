package com.cleanforge.core.scanner

import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.fs.FsOps
import com.cleanforge.core.fs.FsType
import com.cleanforge.core.fs.PathRules
import com.cleanforge.core.fs.StorageWalker
import com.cleanforge.core.fs.WalkLimits
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
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Staged and bounded: (1) group by exact size, files >= [minBytes] only; (2) SHA-256 of the first 64 KB;
 * (3) full SHA-256 only for the survivors. Hard links to the same inode are not "duplicates" (deleting one
 * frees nothing).
 *
 * EVERY copy of a group is listed (oldest first, purely so the list reads the same on each scan; age says
 * nothing about which copy the user values). The scanner never decides which copy stays. The user ticks the copies
 * to delete; PlanBuilder then pins one copy the user left UNTICKED as the keeper (with its scan-time fingerprint),
 * refuses to plan a group whose every copy is ticked, and CleaningEngine refuses to delete a duplicate unless that
 * kept copy is still intact.
 * On demand only (hashing is the heaviest work in the app). Never pre-selected.
 */
class DuplicateScanner(
    private val fs: FsOps,
    private val root: String = PathRules.ROOT,
    private val minBytes: Long = 1024L * 1024L
) : Scanner {
    override val id = "duplicates"
    override val displayName = "Duplicate files"
    override val requirement = Requirement.ALL_FILES_ACCESS
    override val mode = ScanMode.ON_DEMAND

    private val limits = WalkLimits(maxDepth = 32, maxEntries = 100_000)

    override fun scan(): Flow<List<ScanItem>> = flow {
        // Pass 1: size groups.
        val bySize = HashMap<Long, MutableList<StorageWalker.Entry>>()
        var n = 0
        for (dir in LargeFileScanner.ROOTS) {
            for (e in StorageWalker(fs).walk("$root/$dir", limits)) {
                if (++n % 256 == 0) currentCoroutineContext().ensureActive()
                if (e.stat.type != FsType.FILE || e.stat.size < minBytes) continue
                bySize.getOrPut(e.stat.size) { ArrayList() } += e
            }
        }

        // Pass 2: partial hash among same-size files that are not the same inode.
        val byPartial = HashMap<String, MutableList<StorageWalker.Entry>>()
        for ((size, group) in bySize) {
            if (group.size < 2) continue
            if (group.map { it.stat.dev to it.stat.ino }.toSet().size < 2) continue
            for (e in group) {
                val h = sha256(e.path, PARTIAL_BYTES) ?: continue
                byPartial.getOrPut("$size:$h") { ArrayList() } += e
            }
        }

        // Pass 3: full hash.
        val byFull = HashMap<String, MutableList<StorageWalker.Entry>>()
        for ((key, group) in byPartial) {
            if (group.size < 2) continue
            for (e in group) {
                val h = sha256(e.path, Long.MAX_VALUE) ?: continue
                byFull.getOrPut("${key.substringBefore(':')}:$h") { ArrayList() } += e
            }
        }

        val batch = ArrayList<ScanItem>()
        var groupIdx = 0
        for (group in byFull.values) {
            if (group.size < 2) continue
            // Distinct inodes only. Oldest first only for a stable display order, NOT a preference.
            val distinct = group.distinctBy { it.stat.dev to it.stat.ino }
            if (distinct.size < 2) continue
            val ordered = distinct.sortedWith(
                compareBy<StorageWalker.Entry> { it.stat.mtimeMs }.thenBy { it.path.length }.thenBy { it.path }
            )
            val groupId = "dup_${groupIdx++}"
            for (copy in ordered) {
                val others = ordered.filter { it.path != copy.path }
                val shown = others.take(MAX_SHOWN_OTHERS).joinToString("; ") { it.path.removePrefix(root) }
                val more = if (others.size > MAX_SHOWN_OTHERS) " (+${others.size - MAX_SHOWN_OTHERS} more)" else ""
                batch += ScanItem(
                    path = copy.path,
                    sizeBytes = copy.stat.size,
                    category = CleaningCategory.DUPLICATE,
                    reasonForFlagging = "Byte-identical (SHA-256) to ${others.size} other " +
                        (if (others.size == 1) "copy" else "copies") + ": $shown$more. " +
                        "CleanForge does not guess which copy you want: tick the one(s) to delete. " +
                        "At least one copy always stays.",
                    confidence = ConfidenceLevel.MEDIUM,
                    risk = RiskLevel.ELEVATED,
                    deleteMode = DeleteMode.FILE,
                    reversibility = Reversibility.NOT_REVERSIBLE,
                    lastModified = copy.stat.mtimeMs,
                    groupId = groupId,
                    fingerprint = copy.stat
                    // keepPath / keepFingerprint stay null on purpose: the survivor is chosen at PLAN time
                    // from the copies the user did not tick.
                )
                if (batch.size >= BATCH_SIZE) {
                    emit(batch.toList())
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) emit(batch.toList())
    }.flowOn(Dispatchers.IO)

    private suspend fun sha256(path: String, limitBytes: Long): String? {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(path).use { ins ->
                val buf = ByteArray(64 * 1024)
                var remaining = limitBytes
                while (remaining > 0) {
                    val want = if (remaining < buf.size) remaining.toInt() else buf.size
                    val r = ins.read(buf, 0, want)
                    if (r < 0) break
                    md.update(buf, 0, r)
                    remaining -= r
                    currentCoroutineContext().ensureActive()
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    private companion object {
        const val PARTIAL_BYTES = 64L * 1024L
        const val MAX_SHOWN_OTHERS = 3
    }
}
