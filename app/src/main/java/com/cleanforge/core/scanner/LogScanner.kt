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
import java.io.FileInputStream
import java.io.IOException

/**
 * ".log" alone proves nothing. Evidence required (all): .log extension, real file, not modified for
 * [minAgeDays] days, and the first bytes actually look like line-oriented text. Only Download/ is scanned
 * (Documents is personal). Confidence is capped at LOW; always manual review, never pre-selected.
 */
class LogScanner(
    private val fs: FsOps,
    private val root: String = PathRules.ROOT,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val minAgeDays: Int = 30
) : Scanner {
    override val id = "logs"
    override val displayName = "Old log files"
    override val requirement = Requirement.ALL_FILES_ACCESS
    override val mode = ScanMode.ON_DEMAND

    override fun scan(): Flow<List<ScanItem>> = flow {
        val batch = ArrayList<ScanItem>()
        var n = 0
        for (e in StorageWalker(fs).walk("$root/Download")) {
            if (++n % 256 == 0) currentCoroutineContext().ensureActive()
            if (e.stat.type != FsType.FILE || e.stat.size <= 0L) continue
            if (!e.name.endsWith(".log", ignoreCase = true)) continue
            val ageDays = (nowMs() - e.stat.mtimeMs) / DAY_MS
            if (ageDays < minAgeDays) continue
            val head = readHead(e.path) ?: continue
            if (!looksLikeText(head)) continue
            batch += ScanItem(
                path = e.path,
                sizeBytes = e.stat.size,
                category = CleaningCategory.LOG_FILE,
                reasonForFlagging = "Text log file, not modified for $ageDays days. " +
                    "It may still contain something you want to keep.",
                confidence = ConfidenceLevel.LOW,
                risk = RiskLevel.ELEVATED,
                deleteMode = DeleteMode.FILE,
                reversibility = Reversibility.NOT_REVERSIBLE,
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

    private fun readHead(path: String): ByteArray? = try {
        FileInputStream(path).use { ins ->
            val buf = ByteArray(HEAD_BYTES)
            var off = 0
            while (off < buf.size) {
                val r = ins.read(buf, off, buf.size - off)
                if (r < 0) break
                off += r
            }
            buf.copyOf(off)
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    companion object {
        const val HEAD_BYTES = 2048

        /** >= 95% printable/whitespace/UTF-8 bytes, no NUL, and at least one line break. */
        fun looksLikeText(head: ByteArray): Boolean {
            if (head.isEmpty()) return false
            var ok = 0
            var newline = false
            for (b in head) {
                val v = b.toInt() and 0xff
                if (v == 0) return false
                if (v == 0x0a) newline = true
                if (v == 0x09 || v == 0x0a || v == 0x0d || (v in 0x20..0x7e) || v >= 0x80) ok++
            }
            return newline && ok * 100 >= head.size * 95
        }
    }
}
