package com.cleanforge.core

import com.cleanforge.core.fs.DeleteResult
import com.cleanforge.core.fs.DeleteStatus
import com.cleanforge.core.fs.FsStat
import com.cleanforge.core.fs.FsType
import com.cleanforge.core.fs.SizeResult
import com.cleanforge.core.privileged.ListReply
import com.cleanforge.core.privileged.ReplyStatus
import com.cleanforge.core.privileged.SizeReply
import com.cleanforge.core.privileged.StatReply
import com.cleanforge.core.privileged.WireCodec
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WireCodecTest {
    @Test fun `stat survives the trip with every field`() {
        val st = FsStat(FsType.DIRECTORY, 4096L, 1_700_000_000_000L, 64769L, 123456789L)
        val back = WireCodec.decodeStat(WireCodec.encodeStat(StatReply(ReplyStatus.OK, st)))
        assertThat(back.status).isEqualTo(ReplyStatus.OK)
        assertThat(back.stat).isEqualTo(st)
    }

    @Test fun `every non-OK status survives the trip and carries no data`() {
        for (s in ReplyStatus.entries.filter { it != ReplyStatus.OK }) {
            assertThat(WireCodec.decodeStat(WireCodec.encodeStat(StatReply(s))).status).isEqualTo(s)
            assertThat(WireCodec.decodeSize(WireCodec.encodeSize(SizeReply(s))).status).isEqualTo(s)
            assertThat(WireCodec.decodeList(WireCodec.encodeList(ListReply(s, listOf("ignored")))).names).isEmpty()
            assertThat(WireCodec.decodeList(WireCodec.encodeList(ListReply(s))).status).isEqualTo(s)
        }
    }

    @Test fun `malformed or short replies decode to FAILED, never to missing or empty`() {
        assertThat(WireCodec.decodeStat(null).status).isEqualTo(ReplyStatus.FAILED)
        assertThat(WireCodec.decodeStat(longArrayOf()).status).isEqualTo(ReplyStatus.FAILED)
        assertThat(WireCodec.decodeStat(longArrayOf(0L, 1L, 2L)).status).isEqualTo(ReplyStatus.FAILED) // OK but truncated
        assertThat(WireCodec.decodeStat(longArrayOf(77L)).status).isEqualTo(ReplyStatus.FAILED) // unknown status code
        assertThat(WireCodec.decodeSize(null).status).isEqualTo(ReplyStatus.FAILED)
        assertThat(WireCodec.decodeSize(longArrayOf(0L, 5L)).status).isEqualTo(ReplyStatus.FAILED)
        assertThat(WireCodec.decodeList(null).status).isEqualTo(ReplyStatus.FAILED)
        assertThat(WireCodec.decodeList(arrayOf("0")).status).isEqualTo(ReplyStatus.FAILED)
        assertThat(WireCodec.decodeList(arrayOf("x", "0")).status).isEqualTo(ReplyStatus.FAILED)
        assertThat(WireCodec.decodeList(arrayOf("0", "2", "a")).status).isEqualTo(ReplyStatus.FAILED) // bad truncation flag
        assertThat(WireCodec.decodeDelete(null).status).isEqualTo(DeleteStatus.FAILED)
        assertThat(WireCodec.decodeDelete(arrayOf("0", "1")).status).isEqualTo(DeleteStatus.FAILED)
        assertThat(WireCodec.decodeDelete(arrayOf("0", "one", "2", "")).status).isEqualTo(DeleteStatus.FAILED)
        assertThat(WireCodec.decodeDelete(arrayOf("99", "1", "2", "")).status).isEqualTo(DeleteStatus.FAILED) // unknown code
    }

    @Test fun `size keeps the complete flag and the unreadable count`() {
        val exact = WireCodec.decodeSize(WireCodec.encodeSize(SizeReply(ReplyStatus.OK, SizeResult(1234L, true, 0))))
        assertThat(exact.size).isEqualTo(SizeResult(1234L, true, 0))
        assertThat(exact.size!!.isExact).isTrue()
        val cut = WireCodec.decodeSize(WireCodec.encodeSize(SizeReply(ReplyStatus.OK, SizeResult(9L, false, 3))))
        assertThat(cut.size).isEqualTo(SizeResult(9L, false, 3))
        assertThat(cut.size!!.isExact).isFalse()
    }

    @Test fun `names with spaces, unicode, quotes and newlines are carried byte for byte`() {
        val names = listOf("my app", "two  spaces", "\u0394 \u30c6\u30b9\u30c8", "quote\"s'", "semi;colon && pipe | dollar\$HOME", "line\nbreak", "tab\there")
        val back = WireCodec.decodeList(WireCodec.encodeList(ListReply(ReplyStatus.OK, names, truncated = true)))
        assertThat(back.status).isEqualTo(ReplyStatus.OK)
        assertThat(back.names).containsExactlyElementsIn(names).inOrder()
        assertThat(back.truncated).isTrue()
    }

    @Test fun `an empty listing is an empty OK listing and is not confused with a failure`() {
        val back = WireCodec.decodeList(WireCodec.encodeList(ListReply(ReplyStatus.OK, emptyList(), false)))
        assertThat(back.status).isEqualTo(ReplyStatus.OK)
        assertThat(back.names).isEmpty()
        assertThat(back.truncated).isFalse()
    }

    @Test fun `delete results keep status, counts and detail text`() {
        val r = DeleteResult(DeleteStatus.PARTIAL, 7, 4096L, "Could not remove an entry \u2014 \u00e9")
        assertThat(WireCodec.decodeDelete(WireCodec.encodeDelete(r))).isEqualTo(r)
        for (s in DeleteStatus.entries) {
            assertThat(WireCodec.decodeDelete(WireCodec.encodeDelete(DeleteResult(s))).status).isEqualTo(s)
        }
    }

    @Test fun `an over-long detail is cut so a reply can never grow without bound`() {
        val back = WireCodec.decodeDelete(WireCodec.encodeDelete(DeleteResult(DeleteStatus.FAILED, 0, 0, "x".repeat(5_000))))
        assertThat(back.detail.length).isAtMost(300)
    }

    @Test fun `wire codes of existing statuses never change`() {
        // These numbers are part of the app <-> service contract. New statuses may only be appended.
        assertThat(DeleteStatus.entries.map { it.name to it.code }).containsExactly(
            "OK" to 0, "MISSING" to 1, "REFUSED" to 2, "IDENTITY_CHANGED" to 3, "WRONG_TYPE" to 4,
            "FAILED" to 5, "PARTIAL" to 6, "LIMIT_EXCEEDED" to 7, "CANCELLED" to 8, "UNAVAILABLE" to 9, "TIMED_OUT" to 10
        ).inOrder()
        assertThat(ReplyStatus.entries.map { it.name to it.code }).containsExactly(
            "OK" to 0, "NOT_FOUND" to 1, "REFUSED" to 2, "OS_DENIED" to 3, "FAILED" to 4
        ).inOrder()
    }
}
