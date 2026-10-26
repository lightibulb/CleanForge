package com.cleanforge.core.history

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SnapshotCodecTest {
    private val snapshot = Snapshot(
        timestamp = 1_700_000_000_000L,
        rootPath = "/storage/emulated/0",
        totalBytes = 64L shl 30,
        freeBytes = 12L shl 30,
        dirs = listOf(SnapshotEntry("/storage/emulated/0/DCIM", "/storage/emulated/0", "DCIM", 1, 1234L, 5)),
        categoryBytes = mapOf("image" to 100L, "video" to 200L),
        incomplete = true
    )

    @Test fun `round-trips every field`() {
        assertThat(SnapshotCodec.decode(SnapshotCodec.encode(snapshot))).isEqualTo(snapshot)
    }

    @Test fun `a corrupt or hostile file decodes to null as a whole`() {
        assertThat(SnapshotCodec.decode("")).isNull()
        assertThat(SnapshotCodec.decode("not a snapshot")).isNull()
        assertThat(SnapshotCodec.decode("CleanForge-snapshot v1\nmeta\ttimestamp\tx")).isNull()
        assertThat(SnapshotCodec.decode("CleanForge-snapshot v1\ndir\tonly\tthree\tcols")).isNull()
    }

    @Test fun `tabs and newlines in paths are neutralised`() {
        val bad = snapshot.copy(dirs = listOf(SnapshotEntry("a\tb\nc", "p", "n", 1, 0, 0)))
        val decoded = SnapshotCodec.decode(SnapshotCodec.encode(bad))!!
        assertThat(decoded.dirs.single().path).isEqualTo("a b c")
    }
}
