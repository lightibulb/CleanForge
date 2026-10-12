package com.cleanforge.core

import com.cleanforge.core.memory.MemInfoParser
import com.cleanforge.core.memory.MemoryPressure
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MemInfoParserTest {

    private val REALISTIC = """
        MemTotal:        3925568 kB
        MemFree:          234560 kB
        MemAvailable:    1456784 kB
        Buffers:           45632 kB
        Cached:          1234560 kB
        SwapCached:            0 kB
        SwapTotal:       2097148 kB
        SwapFree:        2097148 kB
        HugePages_Total:       0
    """.trimIndent()

    @Test fun `parses a realistic meminfo with kB units into bytes`() {
        val s = MemInfoParser.parse(REALISTIC, timestamp = 1000L)!!
        assertThat(s.totalBytes).isEqualTo(3925568L * 1024)
        assertThat(s.availableBytes).isEqualTo(1456784L * 1024)
        assertThat(s.freeBytes).isEqualTo(234560L * 1024)
        assertThat(s.cachedBytes).isEqualTo(1234560L * 1024)
        assertThat(s.swapTotalBytes).isEqualTo(2097148L * 1024)
        assertThat(s.timestamp).isEqualTo(1000L)
    }

    @Test fun `used is total minus available and never negative`() {
        val s = MemInfoParser.parse(REALISTIC, 0)!!
        assertThat(s.usedBytes).isEqualTo((3925568L - 1456784L) * 1024)
        assertThat(s.usedBytes).isAtLeast(0L)
    }

    @Test fun `missing MemTotal returns null, not a fake zero snapshot`() {
        val noTotal = "MemFree: 100 kB\nMemAvailable: 50 kB"
        assertThat(MemInfoParser.parse(noTotal, 0)).isNull()
    }

    @Test fun `missing MemAvailable falls back to free plus buffers plus cached`() {
        val content = """
            MemTotal: 1000 kB
            MemFree: 200 kB
            Buffers: 100 kB
            Cached: 300 kB
        """.trimIndent()
        val s = MemInfoParser.parse(content, 0)!!
        assertThat(s.availableBytes).isEqualTo((200L + 100L + 300L) * 1024)
    }

    @Test fun `available is clamped to total on corrupted input`() {
        val content = "MemTotal: 100 kB\nMemAvailable: 999999 kB"
        val s = MemInfoParser.parse(content, 0)!!
        assertThat(s.availableBytes).isEqualTo(100L * 1024)
        assertThat(s.usedBytes).isEqualTo(0L)
    }

    @Test fun `garbage lines are skipped without crashing`() {
        val content = "MemTotal: 1024 kB\nnot-a-real-line\n::::\nMemFree: 512 kB"
        val s = MemInfoParser.parse(content, 0)!!
        assertThat(s.totalBytes).isEqualTo(1024L * 1024)
        assertThat(s.freeBytes).isEqualTo(512L * 1024)
    }

    @Test fun `pressure reflects available fraction not used fraction`() {
        // 30% available -> NORMAL, even though 70% is "used" (which is normal on Android).
        val s = MemInfoParser.parse("MemTotal: 1000 kB\nMemAvailable: 300 kB", 0)!!
        assertThat(s.pressure).isEqualTo(MemoryPressure.NORMAL)

        val critical = MemInfoParser.parse("MemTotal: 1000 kB\nMemAvailable: 30 kB", 0)!!
        assertThat(critical.pressure).isEqualTo(MemoryPressure.CRITICAL)
    }

    @Test fun `a zero or negative MemTotal returns null, not a CRITICAL-looking snapshot`() {
        assertThat(MemInfoParser.parse("MemTotal: 0 kB\nMemAvailable: 0 kB", 0)).isNull()
        assertThat(MemInfoParser.parse("MemTotal: -5 kB", 0)).isNull()
    }

    @Test fun `empty content returns null`() {
        assertThat(MemInfoParser.parse("", 0)).isNull()
    }
}