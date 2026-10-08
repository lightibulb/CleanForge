package com.cleanforge.core

import com.cleanforge.core.backend.ArchiveInfo
import com.cleanforge.core.backend.StandardFileBackend
import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.model.CleaningCategory
import com.cleanforge.core.model.ConfidenceLevel
import com.cleanforge.core.model.RiskLevel
import com.cleanforge.core.model.ScanItem
import com.cleanforge.core.scanner.ApkScanner
import com.cleanforge.core.scanner.CacheScanner
import com.cleanforge.core.scanner.CorpseScanner
import com.cleanforge.core.scanner.DuplicateScanner
import com.cleanforge.core.scanner.EmptyDirectoryScanner
import com.cleanforge.core.scanner.LargeFileScanner
import com.cleanforge.core.scanner.LogScanner
import com.cleanforge.core.scanner.Scanner
import com.cleanforge.core.scanner.TempScanner
import com.cleanforge.core.scanner.ThumbnailScanner
import com.cleanforge.testutil.FakeInventory
import com.cleanforge.testutil.InMemoryFs
import com.cleanforge.testutil.NioFsOps
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** Every scanner must justify its findings with evidence beyond a name, and must stay quiet otherwise. */
class ScannerEvidenceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val day = 24L * 60 * 60 * 1000
    private val now = System.currentTimeMillis()

    private suspend fun Scanner.found(): List<ScanItem> = scan().toList().flatten()

    private fun file(rel: String, bytes: ByteArray = "hello world\n".toByteArray(), ageDays: Long = 0): File {
        val f = File(tmp.root, rel)
        f.parentFile.mkdirs()
        Files.write(f.toPath(), bytes)
        f.setLastModified(now - ageDays * day)
        return f
    }

    private val root get() = tmp.root.path

    // ---------- incomplete downloads ----------

    @Test fun `partial downloads need a partial-download extension AND age, while bak files and personal folders are ignored`() = runTest {
        val stale = file("Download/old.crdownload", ageDays = 30)
        file("Download/new.crdownload", ageDays = 1)
        file("Download/notes.bak", ageDays = 365)
        val weak = file("Download/x.tmp", ageDays = 30)
        file("DCIM/y.tmp", ageDays = 365)
        file("Pictures/z.part", ageDays = 365)

        val items = TempScanner(NioFsOps, root, { now }).found()

        assertThat(items.map { it.path }).containsExactly(stale.path, weak.path)
        assertThat(items.first { it.path == stale.path }.confidence).isEqualTo(ConfidenceLevel.MEDIUM)
        assertThat(items.first { it.path == weak.path }.confidence).isEqualTo(ConfidenceLevel.LOW)
        assertThat(items.none { it.isPreselected }).isTrue()
    }

    // ---------- logs ----------

    @Test fun `log files need text content AND age and stay in Downloads`() = runTest {
        val good = file("Download/app.log", "2024-01-01 start\n2024-01-01 stop\n".toByteArray(), ageDays = 60)
        file("Download/binary.log", ByteArray(500) { (it % 7).toByte() }, ageDays = 60)
        file("Download/fresh.log", ageDays = 1)
        file("Documents/notes.log", ageDays = 60)

        val items = LogScanner(NioFsOps, root, { now }).found()

        assertThat(items.map { it.path }).containsExactly(good.path)
        assertThat(items[0].confidence).isEqualTo(ConfidenceLevel.LOW)
        assertThat(items[0].isPreselected).isFalse()
    }

    @Test fun `text sniffing rejects NUL bytes, single-line blobs and mostly-binary data`() {
        assertThat(LogScanner.looksLikeText("line one\nline two\n".toByteArray())).isTrue()
        assertThat(LogScanner.looksLikeText("no newline at all".toByteArray())).isFalse()
        assertThat(LogScanner.looksLikeText(byteArrayOf(65, 10, 0, 66))).isFalse()
        assertThat(LogScanner.looksLikeText(ByteArray(0))).isFalse()
        assertThat(LogScanner.looksLikeText("é日本語\nok\n".toByteArray())).isTrue()
    }

    // ---------- thumbnails ----------

    @Test fun `thumbnail folder is flagged only when it really looks like a thumbnail cache`() = runTest {
        file("DCIM/.thumbnails/1001", ByteArray(2000))
        file("DCIM/.thumbnails/.thumbdata3--1967290299", ByteArray(5000))
        val items = ThumbnailScanner(NioFsOps, root).found()
        assertThat(items).hasSize(1)
        assertThat(items[0].category).isEqualTo(CleaningCategory.THUMBNAIL)
        assertThat(items[0].deleteMode).isEqualTo(DeleteMode.CONTENTS_ONLY)
        assertThat(items[0].isPreselected).isFalse()
    }

    @Test fun `a thumbnails-named folder with sub-folders or big files is left alone`() = runTest {
        file("DCIM/.thumbnails/album/a.jpg", ByteArray(100))
        assertThat(ThumbnailScanner(NioFsOps, root).found()).isEmpty()

        File(tmp.root, "DCIM").deleteRecursively()
        file("DCIM/.thumbnails/huge.mp4", ByteArray(3 * 1024 * 1024))
        assertThat(ThumbnailScanner(NioFsOps, root).found()).isEmpty()
    }

    // ---------- empty folders ----------

    @Test fun `only verifiably empty nested visible folders are listed`() = runTest {
        File(tmp.root, "Download/a/empty").mkdirs()
        file("Download/b/.nomedia", ByteArray(0))
        File(tmp.root, "Download/.hidden").mkdirs()
        file("Download/c/real.txt")

        val items = EmptyDirectoryScanner(NioFsOps, root).found()

        assertThat(items.map { it.path }).containsExactly(File(tmp.root, "Download/a/empty").path)
        assertThat(items[0].deleteMode).isEqualTo(DeleteMode.EMPTY_DIR)
        assertThat(items[0].sizeBytes).isEqualTo(0L)
    }

    // ---------- large files ----------

    @Test fun `large files are suggestions for review, never recommendations`() = runTest {
        file("Download/a.bin", ByteArray(1500))
        val big = file("Download/b.bin", ByteArray(3000))
        val mid = file("Movies/c.bin", ByteArray(2000))
        file("Download/small.bin", ByteArray(500))

        val items = LargeFileScanner(NioFsOps, root, thresholdBytes = 1024, topN = 2).found()

        assertThat(items.map { it.path }).containsExactly(big.path, mid.path).inOrder()
        assertThat(items.all { it.confidence == ConfidenceLevel.UNKNOWN && it.risk == RiskLevel.DANGEROUS }).isTrue()
        assertThat(items.none { it.isPreselected }).isTrue()
    }

    // ---------- duplicates ----------

    @Test fun `every copy is listed, the scanner never picks a keeper, and nothing is pre-selected`() = runTest {
        val content = "identical-content-0123456789".toByteArray()
        val a = file("Download/a.bin", content).also { it.setLastModified(now - 100_000) }
        val b = file("Pictures/b.bin", content).also { it.setLastModified(now - 1_000) }
        file("Download/c.bin", "different-content-0123456789".toByteArray()) // same size, other bytes

        val items = DuplicateScanner(NioFsOps, root, minBytes = 10).found()

        // The OLDEST copy is a deletion candidate too: age does not prove which copy the user values.
        assertThat(items.map { it.path }).containsExactly(a.path, b.path)
        assertThat(items.map { it.groupId }.toSet()).hasSize(1)
        assertThat(items.all { it.category == CleaningCategory.DUPLICATE }).isTrue()
        assertThat(items.all { it.fingerprint != null }).isTrue()
        assertThat(items.all { it.keepPath == null && it.keepFingerprint == null }).isTrue()
        assertThat(items.none { it.isPreselected }).isTrue()
    }

    @Test fun `hard links to one inode are not duplicates`() = runTest {
        val a = file("Download/a.bin", "0123456789abcdef".toByteArray())
        Files.createLink(File(tmp.root, "Download/link.bin").toPath(), a.toPath())
        assertThat(DuplicateScanner(NioFsOps, root, minBytes = 10).found()).isEmpty()
    }

    // ---------- APKs ----------

    @Test fun `apk evidence comes from the platform parser and installed version`() = runTest {
        val installedNewer = file("Download/old.apk", ByteArray(100))
        val notInstalled = file("Download/new.apk", ByteArray(100))
        file("Download/broken.apk", ByteArray(100))
        val inv = FakeInventory(
            versions = mapOf("com.app" to 7L),
            archives = mapOf("old.apk" to ArchiveInfo("com.app", 5), "new.apk" to ArchiveInfo("com.new", 1))
        )

        val items = ApkScanner(NioFsOps, inv, root).found()

        assertThat(items.map { it.path }).containsExactly(installedNewer.path, notInstalled.path)
        assertThat(items.first { it.path == installedNewer.path }.confidence).isEqualTo(ConfidenceLevel.MEDIUM)
        assertThat(items.first { it.path == notInstalled.path }.confidence).isEqualTo(ConfidenceLevel.LOW)
        assertThat(items.none { it.isPreselected }).isTrue()
    }

    // ---------- Android/data scanners (in-memory filesystem standing in for the Shizuku view) ----------

    private val fs = InMemoryFs()
    private val backend = StandardFileBackend(fs)
    private val storage = "/storage/emulated/0"

    @Test fun `leftovers need an unknown package, a plausible package list, a real folder and age`() = runTest {
        fs.file("$storage/Android/data/com.gone.game/files/save.dat", 10)
        fs.file("$storage/Android/obb/com.gone.old/main.obb", 20)
        fs.file("$storage/Android/data/com.recent.gone/x", 5)
        fs.touch("$storage/Android/data/com.recent.gone", now - day)
        fs.file("$storage/Android/data/com.example/cache/x", 5)
        fs.file("$storage/Android/data/files/y", 5)          // not a package name
        fs.file("$storage/Android/data/.nomedia", 0)         // not a package name
        val inv = FakeInventory(FakeInventory.defaultPackages("com.example"))

        val items = CorpseScanner(backend, inv, storage, { now }, minAgeDays = 14).found()

        assertThat(items.map { it.path }).containsExactly(
            "$storage/Android/data/com.gone.game", "$storage/Android/obb/com.gone.old"
        )
        assertThat(items.all { it.confidence == ConfidenceLevel.MEDIUM && it.risk == RiskLevel.ELEVATED }).isTrue()
        assertThat(items.all { it.deleteMode == DeleteMode.TREE && it.requiresShizuku }).isTrue()
        assertThat(items.none { it.isPreselected }).isTrue()
    }

    @Test fun `if the package list cannot be read or looks implausible nothing is flagged`() = runTest {
        fs.file("$storage/Android/data/com.gone.game/files/save.dat", 10)
        fs.file("$storage/Android/data/com.example/cache/x", 10)
        for (broken in listOf<Set<String>?>(null, emptySet(), setOf("a.b", "c.d"))) {
            val inv = FakeInventory(broken)
            assertThat(CorpseScanner(backend, inv, storage, { now }).found()).isEmpty()
            assertThat(CacheScanner(backend, inv, storage).found()).isEmpty()
        }
    }

    @Test fun `caches are limited to installed apps' real cache folders`() = runTest {
        fs.file("$storage/Android/data/com.example/cache/a.bin", 50)
        fs.file("$storage/Android/data/com.whatsapp/cache/w.bin", 70)
        fs.file("$storage/Android/data/com.gone/cache/z.bin", 90)        // not installed
        fs.mkdirs("$storage/Android/data/com.empty/cache")               // nothing to free
        fs.symlink("$storage/Android/data/com.linky/cache")              // not a real directory
        val inv = FakeInventory(FakeInventory.defaultPackages("com.example", "com.whatsapp", "com.empty", "com.linky"))

        val items = CacheScanner(backend, inv, storage).found()

        assertThat(items.map { it.path }).containsExactly(
            "$storage/Android/data/com.example/cache", "$storage/Android/data/com.whatsapp/cache"
        )
        val example = items.first { it.owningPackage == "com.example" }
        val whatsapp = items.first { it.owningPackage == "com.whatsapp" }
        assertThat(example.isPreselected).isTrue()
        assertThat(example.sizeBytes).isEqualTo(50L)
        assertThat(whatsapp.isPreselected).isFalse()
        assertThat(whatsapp.confidence).isEqualTo(ConfidenceLevel.MEDIUM)
        assertThat(items.all { it.deleteMode == DeleteMode.CONTENTS_ONLY && it.requiresShizuku }).isTrue()
    }

    @Test fun `a package Android only remembers as uninstalled-with-data-kept is never an installed app cache`() = runTest {
        fs.file("$storage/Android/data/com.example.game/cache/file.bin", 50)
        fs.file("$storage/Android/data/com.example/cache/a.bin", 50)
        val inv = FakeInventory(
            // What MATCH_UNINSTALLED_PACKAGES reports: the data-kept game is in the list...
            known = FakeInventory.defaultPackages("com.example", "com.example.game"),
            // ...but it is NOT installed.
            installed = FakeInventory.defaultPackages("com.example")
        )

        val items = CacheScanner(backend, inv, storage).found()

        assertThat(items.map { it.path }).containsExactly("$storage/Android/data/com.example/cache")
        assertThat(items.none { it.category == CleaningCategory.APP_CACHE && it.owningPackage == "com.example.game" }).isTrue()
        assertThat(items.none { it.owningPackage == "com.example.game" && it.isPreselected }).isTrue()
        // It is retained data of a package Android still knows, so it is not a "leftover" either.
        assertThat(CorpseScanner(backend, inv, storage, { now }).found()).isEmpty()
    }

    @Test fun `a package that is installed but missing from the known list is never called a leftover`() = runTest {
        fs.file("$storage/Android/data/com.odd.app/files/x", 10)
        val inv = FakeInventory(
            known = FakeInventory.defaultPackages(),
            installed = FakeInventory.defaultPackages("com.odd.app")
        )
        assertThat(CorpseScanner(backend, inv, storage, { now }).found()).isEmpty()
    }
}
