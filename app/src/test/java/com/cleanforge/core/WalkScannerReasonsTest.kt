package com.cleanforge.core

import com.cleanforge.core.fs.FsOps
import com.cleanforge.core.fs.FsProbe
import com.cleanforge.core.fs.FsStat
import com.cleanforge.core.scanner.LargeFileScanner
import com.cleanforge.core.scanner.ThumbnailScanner
import com.cleanforge.testutil.NioFsOps
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** Scanners that walk the filesystem say so when they could not look at everything. */
class WalkScannerReasonsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun write(f: File, bytes: Int) {
        f.parentFile.mkdirs()
        Files.write(f.toPath(), ByteArray(bytes))
    }

    /** The real filesystem, except [denied] exists but cannot be examined. */
    private class DenyingFs(private val denied: String) : FsOps by NioFsOps {
        override fun lstat(path: String): FsStat? = if (path == denied) null else NioFsOps.lstat(path)
        override fun probe(path: String): FsProbe = if (path == denied) FsProbe.Denied else NioFsOps.probe(path)
    }

    @Test fun `a healthy walk reports nothing missing`() = runTest {
        val root = tmp.root.path
        write(File(root, "Download/big.bin"), 2048)
        val scanner = LargeFileScanner(NioFsOps, root, thresholdBytes = 1024)
        assertThat(scanner.scan().toList().flatten()).hasSize(1)
        assertThat(scanner.incompleteReasons()).isEmpty()
    }

    @Test fun `a file that cannot be examined is reported, not silently ignored`() = runTest {
        val root = tmp.root.path
        val big = File(root, "Download/big.bin").also { write(it, 2048) }
        val secret = File(root, "Download/secret.bin").also { write(it, 4096) }
        val scanner = LargeFileScanner(DenyingFs(secret.path), root, thresholdBytes = 1024)
        val found = scanner.scan().toList().flatten()
        assertThat(found.map { it.path }).containsExactly(big.path)
        assertThat(scanner.incompleteReasons().joinToString()).contains("could not be read")
    }

    @Test fun `a thumbnail folder with an entry that cannot be examined is not called a cache`() = runTest {
        val root = tmp.root.path
        write(File(root, "DCIM/.thumbnails/a.jpg"), 100)
        val hidden = File(root, "DCIM/.thumbnails/b").also { write(File(it, "x.jpg"), 100) }
        val scanner = ThumbnailScanner(DenyingFs(hidden.path), root)
        assertThat(scanner.scan().toList().flatten()).isEmpty()
        assertThat(scanner.incompleteReasons()).isNotEmpty()
    }

    @Test fun `a healthy thumbnail folder is still flagged and reports nothing missing`() = runTest {
        val root = tmp.root.path
        write(File(root, "DCIM/.thumbnails/a.jpg"), 100)
        write(File(root, "DCIM/.thumbnails/b.jpg"), 100)
        val scanner = ThumbnailScanner(NioFsOps, root)
        assertThat(scanner.scan().toList().flatten()).hasSize(1)
        assertThat(scanner.incompleteReasons()).isEmpty()
    }
}
