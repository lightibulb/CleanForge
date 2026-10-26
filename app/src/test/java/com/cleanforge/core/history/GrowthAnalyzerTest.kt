package com.cleanforge.core.history

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GrowthAnalyzerTest {
    private fun snap(ts: Long, vararg dirs: Triple<String, Long, Long>, cats: Map<String, Long> = emptyMap(), incomplete: Boolean = false) =
        Snapshot(ts, "/storage/emulated/0", 0, 0,
            dirs.map { (p, b, c) -> SnapshotEntry(p, p.substringBeforeLast('/'), p.substringAfterLast('/'), 1, b, c.toInt()) },
            cats, incomplete)

    @Test fun `growth is delta between snapshots and is sorted by largest delta`() {
        val a = snap(1, Triple("/storage/emulated/0/DCIM", 100, 1), Triple("/storage/emulated/0/Download", 500, 5))
        val b = snap(2, Triple("/storage/emulated/0/DCIM", 900, 9), Triple("/storage/emulated/0/Download", 400, 4))
        val r = GrowthAnalyzer.compare(a, b)
        assertThat(r.directories.first().path).isEqualTo("/storage/emulated/0/DCIM")
        assertThat(r.directories.first().deltaBytes).isEqualTo(800L)
        assertThat(r.directories.first { it.path.endsWith("/Download") }.deltaBytes).isEqualTo(-100L)
    }

    @Test fun `percentChange is null when previous was zero`() {
        val a = snap(1)
        val b = snap(2, Triple("/storage/emulated/0/New", 42, 1))
        val r = GrowthAnalyzer.compare(a, b)
        assertThat(r.directories.first().percentChange).isNull()
    }

    @Test fun `incomplete snapshots make the whole report a lower bound`() {
        val a = snap(1, Triple("/storage/emulated/0/DCIM", 100, 1), incomplete = true)
        val b = snap(2, Triple("/storage/emulated/0/DCIM", 200, 2))
        assertThat(GrowthAnalyzer.compare(a, b).lowerBound).isTrue()
    }
}
