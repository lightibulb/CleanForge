package com.cleanforge.core.privileged

import com.cleanforge.core.fs.DeleteResult
import com.cleanforge.core.fs.DeleteStatus
import com.cleanforge.core.fs.FsStat
import com.cleanforge.core.fs.FsType
import com.cleanforge.core.fs.SizeResult

/**
 * Encoding of service replies into the plain arrays the AIDL interface carries. Pure Kotlin so that both
 * directions are unit-tested on the JVM. A malformed or short reply always decodes to [ReplyStatus.FAILED]; it is
 * never read as "empty" or "missing".
 *
 *  statEx   : [status]  or  [status, type, size, mtimeMs, dev, ino]
 *  sizeEx   : [status]  or  [status, bytes, complete(0/1), unreadable]
 *  listEx   : ["<status>", "<truncated 0/1>", name, name, ...]
 *  deleteEx : ["<status>", "<entriesRemoved>", "<bytesFreed>", "<detail>"]
 */
object WireCodec {
    private const val MAX_DETAIL = 300

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
        return StatReply(ReplyStatus.OK, FsStat(FsType.fromCode(a[1].toInt()), a[2], a[3], a[4], a[5]))
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
        return SizeReply(ReplyStatus.OK, SizeResult(a[1], a[2] == 1L, a[3].coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()))
    }

    fun encodeList(r: ListReply): Array<String> {
        val header = arrayOf(r.status.code.toString(), if (r.truncated) "1" else "0")
        return if (r.status == ReplyStatus.OK) header + r.names.toTypedArray() else header
    }

    fun decodeList(a: Array<String>?): ListReply {
        if (a == null || a.size < 2) return ListReply(ReplyStatus.FAILED)
        val code = a[0].toLongOrNull() ?: return ListReply(ReplyStatus.FAILED)
        val status = ReplyStatus.fromCode(code)
        if (status != ReplyStatus.OK) return ListReply(status)
        val truncated = when (a[1]) {
            "1" -> true
            "0" -> false
            else -> return ListReply(ReplyStatus.FAILED)
        }
        return ListReply(ReplyStatus.OK, a.drop(2), truncated)
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
            return DeleteResult(DeleteStatus.FAILED, detail = "Malformed reply from the Shizuku service")
        }
        return DeleteResult(DeleteStatus.fromCode(code), entries, bytes, a[3])
    }
}
