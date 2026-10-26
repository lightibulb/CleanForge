package com.cleanforge.core.inventory

import com.cleanforge.testutil.InMemoryFs
import com.cleanforge.testutil.ROOT
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class StorageIndexerTest {
    private val fs = InMemoryFs()
    private val indexer = StorageIndexer(fs, maxDepth = 3, maxDirs = 100, topFiles = 5)

    @Test fun `directory aggregates roll up to depth and top files are bounded`() = runTest {
        fs.file("$ROOT/Download/a.bin", 100)
        fs.file("$ROOT/Download/sub/b.bin", 200)
        fs.file("$ROOT/Download/sub/c.bin", 300)
        fs.file("$ROOT/DCIM/d.jpg", 50)
        val idx = indexer.index(ROOT, listOf("Download", "DCIM"), totalBytes = 1_000, freeBytes = 400)

        assertThat(idx.byPath("$ROOT/Download")!!.bytes).isEqualTo(600L)
        assertThat(idx.byPath("$ROOT/Download")!!.fileCount).isEqualTo(3)
        assertThat(idx.byPath("$ROOT/Download/sub")!!.bytes).isEqualTo(500L)
        assertThat(idx.byPath("$ROOT/DCIM")!!.bytes).isEqualTo(50L)
        assertThat(idx.byPath("$ROOT/DCIM")!!.parentPath).isEqualTo(ROOT)
        assertThat(idx.largestFiles.map { it.name }).containsExactly("c.bin", "b.bin", "a.bin", "d.jpg")
        assertThat(idx.categoryTotals.first { it.key == "other" }.bytes).isEqualTo(600L)
        assertThat(idx.incomplete).isFalse()
    }

    @Test fun `a directory that does not exist is not seeded`() = runTest {
        fs.file("$ROOT/Download/a.bin", 10)
        val idx = indexer.index(ROOT, listOf("Download", "Movies"), 0, 0)
        assertThat(idx.byPath("$ROOT/Movies")).isNull()
        assertThat(idx.byPath("$ROOT/Download")).isNotNull()
    }

    @Test fun `depth cap makes the index incomplete and never invents deep nodes`() = runTest {
        fs.file("$ROOT/Download/a/b/c/d/e/file.bin", 100)
        val idx = indexer.index(ROOT, listOf("Download"), 0, 0)
        assertThat(idx.incomplete).isTrue()
        assertThat(idx.directories.none { it.depth > 3 }).isTrue()
        assertThat(idx.byPath("$ROOT/Download/a/b")!!.bytes).isEqualTo(100L)
    }
}
