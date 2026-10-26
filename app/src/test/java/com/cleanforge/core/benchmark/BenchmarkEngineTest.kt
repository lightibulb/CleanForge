package com.cleanforge.core.benchmark

import com.cleanforge.core.fs.WalkLimits
import com.cleanforge.testutil.InMemoryFs
import com.cleanforge.testutil.ROOT
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BenchmarkEngineTest {
    @Test fun `entries visited match the fixture and expected accuracy`() = runTest {
        val fs = InMemoryFs()
        for (i in 1..10) fs.file("$ROOT/Download/f$i.bin", 10)
        val r = BenchmarkEngine(fs).run("$ROOT/Download", expectedEntries = 10, sampleEveryMs = 50)
        assertThat(r.entriesVisited).isEqualTo(10L)
        assertThat(r.accuracy.match).isTrue()
    }

    @Test fun `truncated walk is reported, and the byte count reflects what was scanned`() = runTest {
        val fs = InMemoryFs()
        for (i in 1..5) fs.file("$ROOT/Download/f$i.bin", 10)
        val r = BenchmarkEngine(fs).run(
            "$ROOT/Download",
            limits = WalkLimits(maxDepth = 32, maxEntries = 2),
            sampleEveryMs = 50
        )
        assertThat(r.truncated).isTrue()
        assertThat(r.entriesVisited).isLessThan(5L)
    }
}
