// core/privileged/WireCodec.kt
package com.cleanforge.core.privileged

import com.cleanforge.core.fs.DeleteResult
import com.cleanforge.core.fs.DeleteStatus
import com.cleanforge.core.fs.FsStat
import com.cleanforge.core.fs.FsType
import com.cleanforge.core.fs.SizeResult

object WireCodec {
    private const val MAX_DETAIL = 300
    // Hard limits to prevent OOM from malicious/broken upstream
    private const val MAX_LIST_ENTRIES = 20_000
    private const val MAX_LIST_TOTAL_CHARS = 120_000
    private const val MAX_SINGLE_NAME_LEN = 255

    fun encodeStat(r: StatReply): LongArray {
        val st = r.stat
        return if (r.status == ReplyStatus.OK && st != null) {
            longArrayOf(r.status.code.toLong(), st.type.code.toLong(), st.size, st.mtimeMs, st.dev, st.ino)
        } else {
            longArrayOf(r.status.code.toLong())
        }
    }

    fun decodeStat(a: LongArray?): StatReply {
        if (a == null || a.isEmpty()) return StatReply(ReplyStatus.FAILED)
        val status = ReplyStatus.fromCode(a[0])
        if (status != ReplyStatus.OK) return StatReply(status)
        if (a.size < 6) return StatReply(ReplyStatus.FAILED)
        
        // Sanity check FsType code to avoid enum lookup issues if corrupted
        val typeCode = a[1].toInt()
        val type = FsType.entries.firstOrNull { it.code == typeCode } ?: FsType.OTHER
        
        return StatReply(ReplyStatus.OK, FsStat(type, a[2], a[3], a[4], a[5]))
    }

    fun encodeSize(r: SizeReply): LongArray {
        val sz = r.size
        return if (r.status == ReplyStatus.OK && sz != null) {
            longArrayOf(r.status.code.toLong(), sz.bytes, if (sz.complete) 1L else 0L, sz.unreadable.toLong())
        } else {
            longArrayOf(r.status.code.toLong())
        }
    }

    fun decodeSize(a: LongArray?): SizeReply {
        if (a == null || a.isEmpty()) return SizeReply(ReplyStatus.FAILED)
        val status = ReplyStatus.fromCode(a[0])
        if (status != ReplyStatus.OK) return SizeReply(status)
        if (a.size < 4) return SizeReply(ReplyStatus.FAILED)
        
        // Coerce unreadable count to reasonable int range to prevent overflow/crash
        val unreadable = a[3].coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        return SizeReply(ReplyStatus.OK, SizeResult(a[1], a[2] == 1L, unreadable))
    }

    fun encodeList(r: ListReply): Array<String> {
        val header = arrayOf(r.status.code.toString(), if (r.truncated) "1" else "0")
        return if (r.status == ReplyStatus.OK) header + r.names.toTypedArray() else header
    }

    /**
     * Decodes a list reply with strict bounds checking.
     * - Rejects if too many entries (>20k).
     * - Skips nulls or overly long strings (>255 chars).
     * - Caps total character count to prevent GC pressure/OOM.
     */
    fun decodeList(a: Array<String>?): ListReply {
        if (a == null || a.size < 2) return ListReply(ReplyStatus.FAILED)
        
        val code = a[0].toLongOrNull() ?: return ListReply(ReplyStatus.FAILED)
        val status = ReplyStatus.fromCode(code)
        if (status != ReplyStatus.OK) return ListReply(status)
        
        val truncatedFlag = when (a[1]) {
            "1" -> true
            "0" -> false
            else -> return ListReply(ReplyStatus.FAILED)
        }

        val rawNames = a.drop(2)
        
        // HARD LIMIT: Reject immediately if entry count is absurd
        if (rawNames.size > MAX_LIST_ENTRIES) {
             return ListReply(ReplyStatus.FAILED, emptyList(), true)
        }

        val validNames = ArrayList<String>(minOf(rawNames.size, MAX_LIST_ENTRIES))
        var charCount = 0
        var hitLimit = false

        for (name in rawNames) {
            // CRITICAL FIX: Handle nulls from malformed Binder parcels
            if (name == null) continue 
            
            if (name.length > MAX_SINGLE_NAME_LEN) continue // Skip invalid length
            
            if (charCount + name.length > MAX_LIST_TOTAL_CHARS) {
                hitLimit = true
                break
            }
            
            validNames.add(name)
            charCount += name.length
        }

        // Mark as truncated if we dropped anything due to our internal limits
        val isTruncated = truncatedFlag || hitLimit || validNames.size < rawNames.count { it != null }
        
        return ListReply(ReplyStatus.OK, validNames, isTruncated)
    }

    fun encodeDelete(r: DeleteResult): Array<String> = arrayOf(
        r.status.code.toString(),
        r.entriesRemoved.toString(),
        r.bytesFreed.toString(),
        r.detail.take(MAX_DETAIL)
    )

    fun decodeDelete(a: Array<String>?): DeleteResult {
        if (a == null || a.size < 4) return DeleteResult(DeleteStatus.FAILED, detail = "Malformed reply from the Shizuku service")
        
        val code = a[0].toIntOrNull()
        val entries = a[1].toIntOrNull()
        val bytes = a[2].toLongOrNull()
        
        if (code == null || entries == null || bytes == null) {
            return DeleteResult(DeleteStatus.FAILED, detail = "Malformed numeric fields in reply")
        }
        
        // Sanitize detail string: ensure non-null and truncate
        val detail = a.getOrNull(3)?.take(MAX_DETAIL) ?: ""
        
        return DeleteResult(DeleteStatus.fromCode(code), entries, bytes, detail)
    }
}
