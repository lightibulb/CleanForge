package com.cleanforge.core

import com.cleanforge.core.backend.InstallState
import com.cleanforge.core.backend.PackageInventory
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
import com.cleanforge.core.privileged.CapabilityStatus
import com.cleanforge.core.privileged.DefaultPrivilegedExecutor
import com.cleanforge.core.privileged.FileServiceCore
import com.cleanforge.core.privileged.OperationCapability
import com.cleanforge.core.privileged.PrivilegedExecutor
import com.cleanforge.core.privileged.PrivilegedOperation
import com.cleanforge.core.privileged.TransportException
import com.cleanforge.core.model.confirmedByUser
import com.cleanforge.core.safety.SafetyValidator
import com.cleanforge.testutil.FakeCapabilities
import com.cleanforge.testutil.FakeInventory
import com.cleanforge.testutil.FakeSessionProvider
import com.cleanforge.testutil.InMemoryFs
import com.cleanforge.testutil.LocalPrivilegedFileApi
import com.cleanforge.testutil.ScriptedApi
import com.cleanforge.testutil.ROOT
import com.cleanforge.testutil.scanItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CleaningEngineTest {
    private val fs = InMemoryFs()
    private val caps = FakeCapabilities()
    private val inventory = FakeInventory(FakeInventory.defaultPackages("com.example"))

    /** Privileged work runs the REAL service logic over the same in-memory filesystem. */
    private val apiHooks = ApiHooks()
    private val provider = FakeSessionProvider {
        ScriptedApi(LocalPrivilegedFileApi(FileServiceCore(fs, "com.cleanforge"))).also { api ->
            api.before = { name -> apiHooks.before(name) }
            api.after = { name -> apiHooks.after(name) }
        }
    }

    private class ApiHooks {
        @Volatile var before: (String) -> Unit = {}
        @Volatile var after: (String) -> Unit = {}
    }

    private fun privileged(): PrivilegedExecutor = DefaultPrivilegedExecutor(provider)

    private fun engine(capabilities: com.cleanforge.core.backend.Capabilities = caps, inv: PackageInventory = inventory): CleaningEngine =
        CleaningEngine(SafetyValidator(), StandardFileBackend(fs), privileged(), inv, capabilities)

    private suspend fun clean(vararg items: ScanItem): CleaningReport =
        engine().execute(CleaningPlan(items.toList(), emptyList()).confirmedByUser())
            .filterIsInstance<CleaningProgress.Completed>().first().report

    private suspend fun cleanWith(engine: CleaningEngine, vararg items: ScanItem): CleaningReport =
        engine.execute(CleaningPlan(items.toList(), emptyList()).confirmedByUser())
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

    // ---------- the privileged channel misbehaving (Part 2) ----------

    @Test fun `an unreachable Shizuku service is a failure, never the harmless "already gone"`() = runTest {
        fs.file("$cachePath/a.bin", 40)
        val item = cacheItem()
        provider.notReady = com.cleanforge.core.privileged.SessionResult.NotReady(
            com.cleanforge.core.privileged.UnavailableReason.BIND_TIMEOUT, "The Shizuku service did not start"
        )
        val r = clean(item)
        assertThat(r.outcomes[0].status).isEqualTo(ItemStatus.FAILED)
        assertThat(r.outcomes[0].message).contains("could not be reached")
        assertThat(fs.exists("$cachePath/a.bin")).isTrue()
    }

    @Test fun `a genuinely missing folder is still reported as already gone`() = runTest {
        fs.file("$cachePath/a.bin", 40)
        val item = cacheItem()
        fs.remove("$cachePath/a.bin")
        fs.remove(cachePath)
        val r = clean(item)
        assertThat(r.outcomes[0].status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(r.outcomes[0].message).isEqualTo("Already gone")
    }

    @Test fun `an operation the capability check rejects is skipped with its reason and nothing is deleted`() = runTest {
        fs.file("$cachePath/a.bin", 40)
        val item = cacheItem()
        val unsupported = object : com.cleanforge.core.backend.Capabilities {
            override fun hasAllFilesAccess() = true
            override fun shizukuAuthorized() = true
            override suspend fun readiness(op: PrivilegedOperation) = OperationCapability(
                op, CapabilityStatus.UNSUPPORTED, "The shell cannot read Android/data on this device"
            )
        }
        val r = cleanWith(engine(capabilities = unsupported), item)
        assertThat(r.outcomes[0].status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(r.outcomes[0].message).contains("cannot read Android/data")
        assertThat(fs.exists("$cachePath/a.bin")).isTrue()
    }

    @Test fun `a failed install-state query skips both the cache and the leftover instead of guessing`() = runTest {
        val unknownState = object : PackageInventory by inventory {
            override fun installState(packageName: String) = InstallState.UNKNOWN
        }
        fs.file("$cachePath/a.bin", 40)
        val leftover = "$ROOT/Android/data/com.gone.game"
        fs.file("$leftover/save.dat", 10)
        val cache = cacheItem()
        val corpse = scanned(scanItem(leftover, CleaningCategory.CORPSE_LEFTOVER, pkg = "com.gone.game", shizuku = true))
        val r = cleanWith(engine(inv = unknownState), cache, corpse)
        assertThat(r.outcomes.map { it.status }).containsExactly(ItemStatus.SKIPPED, ItemStatus.SKIPPED)
        assertThat(fs.exists("$cachePath/a.bin")).isTrue()
        assertThat(fs.exists("$leftover/save.dat")).isTrue()
    }

    @Test fun `a folder that exists is never taken as proof the app is gone`() = runTest {
        // The package manager says the app is installed; a leftover-looking folder must not change that.
        val leftover = "$ROOT/Android/data/com.example.game"
        fs.file("$leftover/save.dat", 10)
        val corpse = scanned(scanItem(leftover, CleaningCategory.CORPSE_LEFTOVER, pkg = "com.example.game", shizuku = true))
        inventory.known = FakeInventory.defaultPackages("com.example.game")
        assertThat(clean(corpse).outcomes[0].status).isEqualTo(ItemStatus.SKIPPED)
        assertThat(fs.exists("$leftover/save.dat")).isTrue()
    }

    @Test fun `a partly failed privileged cleanup is PARTIAL with the bytes that really went`() = runTest {
        fs.file("$cachePath/a.bin", 10)
        fs.file("$cachePath/b.bin", 20)
        fs.file("$cachePath/c.bin", 30)
        val item = cacheItem()
        fs.denyRemove += "$cachePath/b.bin"
        val r = clean(item)
        assertThat(r.outcomes[0].status).isEqualTo(ItemStatus.PARTIAL)
        assertThat(r.partial).isEqualTo(1)
        assertThat(r.bytesFreed).isEqualTo(40L)
        assertThat(r.fullySuccessful).isFalse()
    }

    @Test fun `a service that dies mid-delete leaves the item UNVERIFIED and never DELETED`() = runTest {
        fs.file("$cachePath/a.bin", 10)
        val item = cacheItem()
        apiHooks.before = { name ->
            if (name == "delete") throw TransportException(TransportException.Kind.SERVICE_DIED, "The Shizuku service stopped")
        }
        val r = clean(item)
        assertThat(r.outcomes[0].status).isEqualTo(ItemStatus.UNVERIFIED)
        assertThat(r.deleted).isEqualTo(0)
        assertThat(r.fullySuccessful).isFalse()
    }

    @Test fun `a deletion whose end state cannot be read is UNVERIFIED and its bytes are reported as unconfirmed`() = runTest {
        fs.file("$cachePath/a.bin", 10)
        val item = cacheItem()
        val deleted = java.util.concurrent.atomic.AtomicBoolean(false)
        // The delete works; afterwards the check of the folder fails, so the outcome cannot be confirmed.
        apiHooks.after = { name -> if (name == "delete") deleted.set(true) }
        apiHooks.before = { name ->
            if (name == "stat" && deleted.get()) throw TransportException(TransportException.Kind.CALL_FAILED, "reply lost")
        }
        val r = clean(item)
        assertThat(r.outcomes[0].status).isEqualTo(ItemStatus.UNVERIFIED)
        assertThat(r.unverified).isEqualTo(1)
        assertThat(r.bytesFreed).isEqualTo(10L)
        assertThat(r.unconfirmedBytes).isEqualTo(10L)
        assertThat(r.fullySuccessful).isFalse()
    }

    @Test fun `progress says which item is in flight before touching it and carries each finished outcome`() = runTest {
        fs.file("$ROOT/Download/a.bin", 10)
        fs.file("$ROOT/Download/b.bin", 20)
        val a = scanned(scanItem("$ROOT/Download/a.bin"))
        val b = scanned(scanItem("$ROOT/Download/b.bin"))
        val events = engine().execute(CleaningPlan(listOf(a, b), emptyList()).confirmedByUser()).toList()
        val kinds = events.map { it::class.simpleName }
        assertThat(kinds).containsExactly(
            "Started", "ItemStarted", "Progress", "ItemStarted", "Progress", "Completed"
        ).inOrder()
        val progress = events.filterIsInstance<CleaningProgress.Progress>()
        assertThat(progress.map { it.outcome!!.status }).containsExactly(ItemStatus.DELETED, ItemStatus.DELETED)
        assertThat(progress.last().freedBytes).isEqualTo(30L)
        assertThat((events[1] as CleaningProgress.ItemStarted).item.path).isEqualTo(a.path)
    }
}
