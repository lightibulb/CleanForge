package com.cleanforge.core

import com.cleanforge.core.backend.StandardFileBackend
import com.cleanforge.core.cleaning.CleaningEngine
import com.cleanforge.core.cleaning.CleaningProgress
import com.cleanforge.core.cleaning.PlanBuilder
import com.cleanforge.core.model.CleaningCategory
import com.cleanforge.core.model.CleaningPlan
import com.cleanforge.core.model.CleaningReport
import com.cleanforge.core.model.ConfidenceLevel
import com.cleanforge.core.model.ItemStatus
import com.cleanforge.core.model.RiskLevel
import com.cleanforge.core.model.ScanItem
import com.cleanforge.core.model.confirmedByUser
import com.cleanforge.core.safety.SafetyValidator
import com.cleanforge.testutil.FakeCapabilities
import com.cleanforge.testutil.FakeInventory
import com.cleanforge.testutil.InMemoryFs
import com.cleanforge.testutil.ROOT
import com.cleanforge.testutil.scanItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CleaningEngineTest {
    private val fs = InMemoryFs()
    private val caps = FakeCapabilities()
    private val inventory = FakeInventory(FakeInventory.defaultPackages("com.example"))

    private fun engine(): CleaningEngine {
        val backend = StandardFileBackend(fs)
        return CleaningEngine(SafetyValidator(), backend, backend, inventory, caps)
    }

    private suspend fun clean(vararg items: ScanItem): CleaningReport =
        engine().execute(CleaningPlan(items.toList(), emptyList()).confirmedByUser())
            .filterIsInstance<CleaningProgress.Completed>().first().report

    /** A scan-time snapshot, like the scanners record. */
    private fun scanned(item: ScanItem) = item.copy(fingerprint = fs.lstat(item.path))

    private val cachePath = "$ROOT/Android/data/com.example/cache"
    private fun cacheItem() = scanned(
        scanItem(cachePath, CleaningCategory.APP_CACHE, ConfidenceLevel.HIGH, RiskLevel.SAFE, "com.example", shizuku = true)
    )

    @Test fun `cache contents are cleared, the folder stays, and bytes are reported from reality`() = runTest {
        fs.file("$cachePath/a.bin", size = 40)
        fs.file("$cachePath/sub/b.bin", size = 60)
        val item = cacheItem()
        val r = clean(item)
        assertThat(r.deleted).isEqualTo(1)
        assertThat(r.bytesFreed).isEqualTo(100L)
        assertThat(fs.exists(cachePath)).isTrue()
        assertThat(fs.list(cachePath)).isEmpty()
    }

    @Test fun `item without a scan snapshot is skipped`() = runTest {
        fs.file("$cachePath/a.bin")
        val r = clean(scanItem(cachePath, CleaningCategory.APP_CACHE, ConfidenceLevel.HIGH, RiskLevel.SAFE, "com.example", shizuku = true))
        assertThat(r.outcomes[0].status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(fs.exists("$cachePath/a.bin")).isTrue()
    }

    @Test fun `file changed since the scan is skipped and survives`() = runTest {
        val p = "$ROOT/Download/video.mp4"
        fs.file(p, size = 500)
        val item = scanned(scanItem(p))
        fs.resize(p, 900) // user kept writing to it
        val r = clean(item)
        assertThat(r.outcomes[0].status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(fs.exists(p)).isTrue()
    }

    @Test fun `file swapped for a symlink after the scan is skipped`() = runTest {
        val p = "$ROOT/Download/doc.pdf"
        fs.file(p)
        val item = scanned(scanItem(p))
        fs.replaceWithSymlink(p)
        val r = clean(item)
        assertThat(r.outcomes[0].status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(fs.exists(p)).isTrue()
    }

    @Test fun `file that vanished is reported not crashed`() = runTest {
        val p = "$ROOT/Download/gone.bin"
        fs.file(p)
        val item = scanned(scanItem(p))
        fs.remove(p)
        val r = clean(item)
        assertThat(r.outcomes[0].status).isEqualTo(ItemStatus.SKIPPED)
    }

    @Test fun `partial failure is reported honestly`() = runTest {
        val a = "$ROOT/Download/a.bin"
        val b = "$ROOT/Download/b.bin"
        fs.file(a); fs.file(b)
        val items = listOf(scanned(scanItem(a)), scanned(scanItem(b)))
        fs.denyRemove += b
        val r = clean(*items.toTypedArray())
        assertThat(r.deleted).isEqualTo(1)
        assertThat(r.failed).isEqualTo(1)
        assertThat(r.fullySuccessful).isFalse()
        assertThat(fs.exists(b)).isTrue()
    }

    @Test fun `leftover whose app got reinstalled is skipped`() = runTest {
        val dir = "$ROOT/Android/data/com.gone.game"
        fs.file("$dir/save.dat")
        val item = scanned(scanItem(dir, CleaningCategory.CORPSE_LEFTOVER, pkg = "com.gone.game", shizuku = true))
        inventory.known = FakeInventory.defaultPackages("com.gone.game")
        val r = clean(item)
        assertThat(r.outcomes[0].status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(fs.exists("$dir/save.dat")).isTrue()
    }

    @Test fun `leftover is skipped when the package list cannot be trusted`() = runTest {
        val dir = "$ROOT/Android/data/com.gone.game"
        fs.file("$dir/save.dat")
        val item = scanned(scanItem(dir, CleaningCategory.CORPSE_LEFTOVER, pkg = "com.gone.game", shizuku = true))
        inventory.known = null
        assertThat(clean(item).outcomes[0].status).isEqualTo(ItemStatus.SKIPPED)
        inventory.known = setOf("only.one")
        assertThat(clean(item).outcomes[0].status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(fs.exists("$dir/save.dat")).isTrue()
    }

    @Test fun `true leftover is removed completely`() = runTest {
        val dir = "$ROOT/Android/data/com.gone.game"
        fs.file("$dir/files/save.dat")
        val item = scanned(scanItem(dir, CleaningCategory.CORPSE_LEFTOVER, pkg = "com.gone.game", shizuku = true))
        assertThat(clean(item).outcomes[0].status).isEqualTo(ItemStatus.DELETED)
        assertThat(fs.exists(dir)).isFalse()
    }

    @Test fun `duplicate is skipped when the kept copy changed or vanished and deleted when intact`() = runTest {
        val keep = "$ROOT/Pictures/a.jpg"
        val dup = "$ROOT/Download/a.jpg"
        fs.file(keep, 50, 1); fs.file(dup, 50, 2)
        val item = scanned(scanItem(dup, CleaningCategory.DUPLICATE, keepPath = keep, keepFingerprint = fs.lstat(keep)))
        fs.resize(keep, 51)
        assertThat(clean(item).outcomes[0].status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(fs.exists(dup)).isTrue()
        fs.resize(keep, 50)
        assertThat(clean(item).outcomes[0].status).isEqualTo(ItemStatus.DELETED)
        assertThat(fs.exists(keep)).isTrue()
    }

    @Test fun `shizuku items are skipped when shizuku is gone and file items when access is revoked`() = runTest {
        fs.file("$cachePath/a.bin")
        val cache = cacheItem()
        caps.shizuku = false
        assertThat(clean(cache).outcomes[0].status).isEqualTo(ItemStatus.SKIPPED)
        val p = "$ROOT/Download/a.bin"
        fs.file(p)
        val file = scanned(scanItem(p))
        caps.allFiles = false
        assertThat(clean(file).outcomes[0].status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(fs.exists(p)).isTrue()
    }

    @Test fun `policy is re-applied at execution time even for a hand-built plan`() = runTest {
        // A plan assembled without PlanBuilder must still be stopped by the engine's own revalidation.
        fs.file("$ROOT/DCIM/Camera/IMG.jpg")
        val evil = scanned(scanItem("$ROOT/DCIM/Camera", CleaningCategory.APP_CACHE, ConfidenceLevel.HIGH, RiskLevel.SAFE, "com.example"))
        assertThat(clean(evil).outcomes[0].status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(fs.exists("$ROOT/DCIM/Camera/IMG.jpg")).isTrue()
    }

    // ---------- package state at deletion time (H1) ----------

    @Test fun `app cache is refused when the app was uninstalled after the scan even though Android still knows the package`() = runTest {
        fs.file("$cachePath/a.bin", 40)
        val item = cacheItem()
        // The user uninstalls the app but keeps its data: still in the MATCH_UNINSTALLED_PACKAGES list, no longer installed.
        inventory.known = FakeInventory.defaultPackages("com.example")
        inventory.installed = FakeInventory.defaultPackages()
        val r = clean(item)
        assertThat(r.outcomes[0].status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(fs.exists("$cachePath/a.bin")).isTrue()
    }

    @Test fun `app cache is refused when Android no longer knows the package at all`() = runTest {
        fs.file("$cachePath/a.bin", 40)
        val item = cacheItem()
        inventory.known = FakeInventory.defaultPackages()
        assertThat(clean(item).outcomes[0].status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(fs.exists("$cachePath/a.bin")).isTrue()
    }

    @Test fun `app cache of a package that is installed is still cleaned`() = runTest {
        fs.file("$cachePath/a.bin", 40)
        inventory.known = FakeInventory.defaultPackages("com.example", "com.other.kept")
        inventory.installed = FakeInventory.defaultPackages("com.example")
        assertThat(clean(cacheItem()).outcomes[0].status).isEqualTo(ItemStatus.DELETED)
    }

    @Test fun `leftover is refused when the package reports installed even if the known list lacks it`() = runTest {
        val dir = "$ROOT/Android/data/com.gone.game"
        fs.file("$dir/save.dat")
        val item = scanned(scanItem(dir, CleaningCategory.CORPSE_LEFTOVER, pkg = "com.gone.game", shizuku = true))
        inventory.installed = FakeInventory.defaultPackages("com.gone.game")
        assertThat(clean(item).outcomes[0].status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(fs.exists("$dir/save.dat")).isTrue()
    }

    // ---------- duplicates: the user, not the scanner, decides which copy goes (M2) ----------

    @Test fun `the user may delete the OLDEST copy and the copy they left alone survives`() = runTest {
        val oldest = "$ROOT/Pictures/family-photo.jpg"
        val newer = "$ROOT/Download/family-photo.jpg"
        fs.file(oldest, 50, mtime = 1)
        fs.file(newer, 50, mtime = 2)
        val a = scanned(scanItem(oldest, CleaningCategory.DUPLICATE, ConfidenceLevel.MEDIUM, RiskLevel.ELEVATED, groupId = "g"))
        val b = scanned(scanItem(newer, CleaningCategory.DUPLICATE, ConfidenceLevel.MEDIUM, RiskLevel.ELEVATED, groupId = "g"))

        val plan = PlanBuilder(SafetyValidator()).build(selected = listOf(a), scanned = listOf(a, b))
        assertThat(plan.items.map { it.path }).containsExactly(oldest)
        assertThat(plan.items[0].keepPath).isEqualTo(newer)

        val report = engine().execute(plan.confirmedByUser())
            .filterIsInstance<CleaningProgress.Completed>().first().report
        assertThat(report.deleted).isEqualTo(1)
        assertThat(fs.exists(oldest)).isFalse()
        assertThat(fs.exists(newer)).isTrue()
    }

    @Test fun `a duplicate whose kept copy is the very same file is refused`() = runTest {
        val p = "$ROOT/Download/a.jpg"
        fs.file(p)
        val item = scanned(scanItem(p, CleaningCategory.DUPLICATE, keepPath = p, keepFingerprint = fs.lstat(p)))
        assertThat(clean(item).outcomes[0].status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(fs.exists(p)).isTrue()
    }
}
