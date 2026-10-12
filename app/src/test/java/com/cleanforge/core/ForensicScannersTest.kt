package com.cleanforge.core

import com.cleanforge.core.backend.DirListing
import com.cleanforge.core.backend.FileReadBackend
import com.cleanforge.core.backend.FsResult
import com.cleanforge.core.backend.StandardFileBackend
import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.fs.FsStat
import com.cleanforge.core.fs.SizeResult
import com.cleanforge.core.model.CleaningCategory
import com.cleanforge.core.model.ConfidenceLevel
import com.cleanforge.core.model.RiskLevel
import com.cleanforge.core.model.ScanItem
import com.cleanforge.core.safety.SafetyValidator
import com.cleanforge.core.scanner.InstagramScanner
import com.cleanforge.core.scanner.Requirement
import com.cleanforge.core.scanner.Scanner
import com.cleanforge.core.scanner.TelegramScanner
import com.cleanforge.core.scanner.TikTokScanner
import com.cleanforge.core.scanner.TwitterXScanner
import com.cleanforge.core.scanner.WhatsAppScanner
import com.cleanforge.testutil.FakeInventory
import com.cleanforge.testutil.InMemoryFs
import com.cleanforge.testutil.ROOT
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Part 3: each forensic scanner flags only its allowlisted folders, respects age, and its output survives the validator. */
class ForensicScannersTest {
    private val day = 24L * 60 * 60 * 1000
    private val hour = 60L * 60 * 1000
    private val now = 100 * day
    private val validator = SafetyValidator()

    private suspend fun Scanner.found(): List<ScanItem> = scan().toList().flatten()

    private fun backend(fs: InMemoryFs) = StandardFileBackend(fs)

    private fun wa(fs: InMemoryFs, rel: String, ageMs: Long, pkg: String = "com.whatsapp"): String {
        val path = "$ROOT/Android/media/$pkg/WhatsApp/$rel"
        fs.file(path, size = 10, mtime = now - ageMs)
        return path
    }

    private fun assertAllValid(items: List<ScanItem>) {
        for (item in items) assertThat(validator.rejectionReason(item)).isNull()
    }

    // ---------- WhatsApp ----------

    @Test fun `whatsapp statuses need 24 hours, sent folders need no age, and everything else is ignored`() = runTest {
        val fs = InMemoryFs()
        val oldStatus = wa(fs, "Media/.Statuses/old.mp4", 25 * hour)
        wa(fs, "Media/.Statuses/new.mp4", 2 * hour)
        val sentVideo = wa(fs, "Media/WhatsApp Video/Sent/v.mp4", 1 * hour)
        val sentImage = wa(fs, "Media/WhatsApp Images/Sent/i.jpg", 30 * day)
        wa(fs, "Media/WhatsApp Images/IMG-received.jpg", 90 * day)       // received media: not ours
        wa(fs, "Media/WhatsApp Documents/Sent/d.pdf", 90 * day)          // folder not on the allowlist
        wa(fs, "Backups/other.bin", 90 * day)

        val scanner = WhatsAppScanner(backend(fs), FakeInventory(known = FakeInventory.defaultPackages("com.whatsapp")), ROOT, { now })
        val items = scanner.found()

        assertThat(items.map { it.path }).containsExactly(oldStatus, sentVideo, sentImage)
        assertAllValid(items)
        assertThat(items.all { it.category == CleaningCategory.FORENSIC_MEDIA }).isTrue()
        assertThat(items.all { it.deleteMode == DeleteMode.FILE }).isTrue()
        assertThat(items.none { it.requiresShizuku }).isTrue()
        assertThat(items.none { it.isPreselected }).isTrue()
        assertThat(items.all { it.owningPackage == "com.whatsapp" }).isTrue()
        assertThat(scanner.requirement).isEqualTo(Requirement.ALL_FILES_ACCESS)
    }

    @Test fun `sent media is not claimed to be safe because it may be the only copy`() = runTest {
        val fs = InMemoryFs()
        wa(fs, "Media/WhatsApp Images/Sent/i.jpg", 5 * day)
        val item = WhatsAppScanner(backend(fs), FakeInventory(known = FakeInventory.defaultPackages("com.whatsapp")), ROOT, { now }).found().single()

        assertThat(item.risk).isEqualTo(RiskLevel.ELEVATED)
        assertThat(item.confidence).isEqualTo(ConfidenceLevel.MEDIUM)
        assertThat(item.reasonForFlagging).doesNotContain("Already in Gallery")
    }

    @Test fun `old chat backups keep the newest msgstore backup whatever its age`() = runTest {
        val fs = InMemoryFs()
        val newest = wa(fs, "Databases/msgstore-2026-09-01.1.db.crypt14", 20 * day)
        val older = wa(fs, "Databases/msgstore-2026-08-01.1.db.crypt14", 30 * day)
        val oldest = wa(fs, "Databases/msgstore-2026-07-01.1.db.crypt14", 40 * day)
        wa(fs, "Databases/msgstore.db.crypt14", 90 * day)   // live backup: no dash, never proposed
        wa(fs, "Databases/wa.db", 90 * day)

        val items = WhatsAppScanner(backend(fs), FakeInventory(known = FakeInventory.defaultPackages("com.whatsapp")), ROOT, { now }).found()

        assertThat(items.map { it.path }).containsExactly(older, oldest)
        assertThat(items.map { it.path }).doesNotContain(newest)
        assertThat(items.all { it.risk == RiskLevel.ELEVATED }).isTrue()
        assertAllValid(items)
    }

    @Test fun `recent chat backups are not proposed`() = runTest {
        val fs = InMemoryFs()
        wa(fs, "Databases/msgstore-2026-10-10.1.db.crypt14", 2 * day)
        wa(fs, "Databases/msgstore-2026-10-05.1.db.crypt14", 6 * day)

        val items = WhatsAppScanner(backend(fs), FakeInventory(known = FakeInventory.defaultPackages("com.whatsapp")), ROOT, { now }).found()

        assertThat(items).isEmpty()
    }

    @Test fun `when both whatsapp and business are installed both are scanned`() = runTest {
        val fs = InMemoryFs()
        val a = wa(fs, "Media/.Statuses/a.mp4", 3 * day, pkg = "com.whatsapp")
        val b = wa(fs, "Media/.Statuses/b.mp4", 3 * day, pkg = "com.whatsapp.w4b")

        val inv = FakeInventory(known = FakeInventory.defaultPackages("com.whatsapp", "com.whatsapp.w4b"))
        val items = WhatsAppScanner(backend(fs), inv, ROOT, { now }).found()

        assertThat(items.map { it.path }).containsExactly(a, b)
        assertAllValid(items)
    }

    @Test fun `an app that is not installed, or uninstalled with data kept, yields nothing`() = runTest {
        val fs = InMemoryFs()
        wa(fs, "Media/.Statuses/a.mp4", 3 * day)

        val notInstalled = WhatsAppScanner(backend(fs), FakeInventory(known = FakeInventory.defaultPackages()), ROOT, { now })
        assertThat(notInstalled.found()).isEmpty()
        assertThat(notInstalled.incompleteReasons()).isEmpty()

        val dataKept = WhatsAppScanner(
            backend(fs),
            FakeInventory(known = FakeInventory.defaultPackages("com.whatsapp"), installed = FakeInventory.defaultPackages()),
            ROOT, { now }
        )
        assertThat(dataKept.found()).isEmpty()
    }

    @Test fun `a failed package query is reported as could-not-check, not as nothing found`() = runTest {
        val fs = InMemoryFs()
        wa(fs, "Media/.Statuses/a.mp4", 3 * day)

        val scanner = WhatsAppScanner(backend(fs), FakeInventory(known = null), ROOT, { now })

        assertThat(scanner.found()).isEmpty()
        assertThat(scanner.incompleteReasons()).isNotEmpty()
    }

    @Test fun `sub-folders are reported but never proposed`() = runTest {
        val fs = InMemoryFs()
        wa(fs, "Media/.Statuses/file.mp4", 3 * day)
        fs.mkdirs("$ROOT/Android/media/com.whatsapp/WhatsApp/Media/.Statuses/nested")

        val scanner = WhatsAppScanner(backend(fs), FakeInventory(known = FakeInventory.defaultPackages("com.whatsapp")), ROOT, { now })
        val items = scanner.found()

        assertThat(items).hasSize(1)
        assertThat(scanner.incompleteReasons().joinToString()).contains("sub-folder")
    }

    @Test fun `a folder that cannot be read is reported, not silently treated as empty`() = runTest {
        val denied = object : FileReadBackend {
            override val name = "denied"
            override suspend fun lstat(path: String): FsResult<FsStat> = FsResult.Denied("no")
            override suspend fun list(path: String): FsResult<DirListing> = FsResult.Denied("no")
            override suspend fun directorySize(path: String): FsResult<SizeResult> = FsResult.Denied("no")
        }
        val scanner = WhatsAppScanner(denied, FakeInventory(known = FakeInventory.defaultPackages("com.whatsapp")), ROOT, { now })

        assertThat(scanner.found()).isEmpty()
        assertThat(scanner.incompleteReasons().joinToString()).contains("could not be read")
    }

    @Test fun `a dead privileged channel stops the scan and says so`() = runTest {
        val dead = object : FileReadBackend {
            override val name = "dead"
            override suspend fun lstat(path: String): FsResult<FsStat> = FsResult.Unavailable("Shizuku stopped")
            override suspend fun list(path: String): FsResult<DirListing> = FsResult.Unavailable("Shizuku stopped")
            override suspend fun directorySize(path: String): FsResult<SizeResult> = FsResult.Unavailable("Shizuku stopped")
        }
        val scanner = TikTokScanner(dead, FakeInventory(known = FakeInventory.defaultPackages("com.zhiliaoapp.musically")), ROOT, { now })

        assertThat(scanner.found()).isEmpty()
        assertThat(scanner.incompleteReasons().joinToString()).contains("Shizuku stopped")
    }

    // ---------- Telegram ----------

    @Test fun `telegram needs 14 days, covers only its four folders, and is elevated`() = runTest {
        val fs = InMemoryFs()
        fun tg(rel: String, age: Long): String {
            val p = "$ROOT/Android/media/org.telegram.messenger/Telegram/$rel"
            fs.file(p, 10, now - age)
            return p
        }
        val old = tg("Telegram Video/old.mp4", 15 * day)
        tg("Telegram Video/new.mp4", 13 * day)
        val doc = tg("Telegram Documents/d.pdf", 30 * day)
        tg("Telegram Stories/s.mp4", 30 * day)    // not on the allowlist

        val items = TelegramScanner(backend(fs), FakeInventory(known = FakeInventory.defaultPackages("org.telegram.messenger")), ROOT, { now }).found()

        assertThat(items.map { it.path }).containsExactly(old, doc)
        assertThat(items.all { it.risk == RiskLevel.ELEVATED && it.confidence == ConfidenceLevel.MEDIUM }).isTrue()
        assertThat(items.none { it.isPreselected }).isTrue()
        assertAllValid(items)
    }

    // ---------- Android/data scanners (dormant: not registered, see TikTokScanner) ----------

    @Test fun `tiktok flags cache and music files after 7 days, needs Shizuku, and passes the validator`() = runTest {
        val fs = InMemoryFs()
        val pkg = "com.zhiliaoapp.musically"
        val cache = "$ROOT/Android/data/$pkg/cache/v.bin".also { fs.file(it, 10, now - 8 * day) }
        val music = "$ROOT/Android/data/$pkg/files/.music/m.mp3".also { fs.file(it, 10, now - 8 * day) }
        "$ROOT/Android/data/$pkg/cache/fresh.bin".also { fs.file(it, 10, now - 1 * day) }
        "$ROOT/Android/data/$pkg/files/other/x.bin".also { fs.file(it, 10, now - 90 * day) }

        val scanner = TikTokScanner(backend(fs), FakeInventory(known = FakeInventory.defaultPackages(pkg)), ROOT, { now })
        val items = scanner.found()

        assertThat(items.map { it.path }).containsExactly(cache, music)
        assertThat(items.all { it.requiresShizuku }).isTrue()
        assertThat(scanner.requirement).isEqualTo(Requirement.SHIZUKU)
        assertAllValid(items)
    }

    @Test fun `twitter and instagram use their own thresholds`() = runTest {
        val fs = InMemoryFs()
        val x = "$ROOT/Android/data/com.twitter.android/cache/a.bin".also { fs.file(it, 10, now - 8 * day) }
        "$ROOT/Android/data/com.instagram.android/cache/a.bin".also { fs.file(it, 10, now - 13 * day) }
        val igOld = "$ROOT/Android/data/com.instagram.lite/cache/b.bin".also { fs.file(it, 10, now - 15 * day) }

        val inv = FakeInventory(known = FakeInventory.defaultPackages("com.twitter.android", "com.instagram.android", "com.instagram.lite"))
        assertThat(TwitterXScanner(backend(fs), inv, ROOT, { now }).found().map { it.path }).containsExactly(x)
        assertThat(InstagramScanner(backend(fs), inv, ROOT, { now }).found().map { it.path }).containsExactly(igOld)
    }
}
