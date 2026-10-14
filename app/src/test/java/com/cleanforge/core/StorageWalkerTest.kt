package com.cleanforge.core

import com.cleanforge.core.fs.FsOps
import com.cleanforge.core.fs.FsProbe
import com.cleanforge.core.fs.FsStat
import com.cleanforge.core.fs.StorageWalker
import com.cleanforge.testutil.NioFsOps
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class StorageWalkerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun write(f: File, text: String) {
        f.parentFile.mkdirs()
        Files.write(f.toPath(), text.toByteArray())
    }

    /**
     * The real filesystem, except: [denied] exists but cannot be examined, [vanished] is gone by the time it is
     * examined, and everything under [mount] reports another device.
     */
    private class QuirkyFs(
        private val denied: String? = null,
        private val vanished: String? = null,
        private val mount: String? = null
    ) : FsOps by NioFsOps {
        private fun tweak(path: String, s: FsStat) = if (mount != null && path.startsWith(mount)) s.copy(dev = 4242L) else s

        override fun lstat(path: String): FsStat? =
            if (path == denied || path == vanished) null else NioFsOps.lstat(path)?.let { tweak(path, it) }

        override fun probe(path: String): FsProbe = when (path) {
            denied -> FsProbe.Denied
            vanished -> FsProbe.Missing
            else -> when (val r = NioFsOps.probe(path)) {
                is FsProbe.Found -> FsProbe.Found(tweak(path, r.stat))
                else -> r
            }
        }
    }

    private fun root() = File(tmp.root, "root").also {
        write(File(it, "a.txt"), "1234")
        write(File(it, "b.bin"), "123456789")
    }

    @Test fun `an entry that cannot be examined makes the size a lower bound instead of counting zero`() {
        val root = root()
        val r = StorageWalker(QuirkyFs(denied = File(root, "b.bin").path)).sizeOf(root.path)!!
        assertThat(r.bytes).isEqualTo(4L)
        assertThat(r.unreadable).isEqualTo(1)
        assertThat(r.isExact).isFalse()
    }

    @Test fun `an entry that vanished between listing and examining is not counted as unreadable`() {
        val root = root()
        val r = StorageWalker(QuirkyFs(vanished = File(root, "b.bin").path)).sizeOf(root.path)!!
        assertThat(r.bytes).isEqualTo(4L)
        assertThat(r.unreadable).isEqualTo(0)
        assertThat(r.isExact).isTrue()
    }

    @Test fun `a mount point is not entered and the size is reported as a lower bound`() {
        val root = root()
        write(File(root, "mnt/other-filesystem.txt"), "123456")
        val r = StorageWalker(QuirkyFs(mount = File(root, "mnt").path)).sizeOf(root.path)!!
        assertThat(r.bytes).isEqualTo(13L) // a.txt + b.bin only
        assertThat(r.unreadable).isEqualTo(1)
        assertThat(r.isExact).isFalse()
    }

    @Test fun `a plain tree is measured exactly`() {
        val root = root()
        write(File(root, "sub/deep/c.txt"), "12")
        val r = StorageWalker(NioFsOps).sizeOf(root.path)!!
        assertThat(r.bytes).isEqualTo(15L)
        assertThat(r.isExact).isTrue()
    }
}
