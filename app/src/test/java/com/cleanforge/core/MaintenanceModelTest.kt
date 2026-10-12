package com.cleanforge.core

import com.cleanforge.core.backend.DirListing
import com.cleanforge.core.backend.FsResult
import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.fs.DeleteResult
import com.cleanforge.core.fs.DeleteStatus
import com.cleanforge.core.fs.FsStat
import com.cleanforge.core.fs.FsType
import com.cleanforge.core.model.CleaningCategory
import com.cleanforge.core.model.ConfidenceLevel
import com.cleanforge.core.model.RiskLevel
import com.cleanforge.core.privileged.ExternalArea
import com.cleanforge.core.privileged.FailureKind
import com.cleanforge.core.privileged.MaintenanceRequest
import com.cleanforge.core.privileged.MaintenanceResult
import com.cleanforge.core.privileged.PostState
import com.cleanforge.core.privileged.PrivilegedOperation
import com.cleanforge.core.privileged.classifyDeletion
import com.cleanforge.core.privileged.deleteMode
import com.cleanforge.core.privileged.observePostState
import com.cleanforge.core.privileged.targetPathOrNull
import com.cleanforge.core.privileged.toMaintenanceRequest
import com.cleanforge.testutil.FakeInventory
import com.cleanforge.testutil.ROOT
import com.cleanforge.testutil.scanItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MaintenanceModelTest {
    private val fp = FsStat(FsType.DIRECTORY, 0, 1_000, 1L, 42L)
    private val known = FakeInventory.defaultPackages("com.other")

    // ------------------------------------------------------------------ request building

    @Test fun `a request derives its path itself from a validated package name`() {
        assertThat(MaintenanceRequest.ClearExternalAppCache("com.example", fp).targetPathOrNull())
            .isEqualTo("$ROOT/Android/data/com.example/cache")
        assertThat(MaintenanceRequest.RemoveUninstalledAppData(ExternalArea.DATA, "com.gone", fp, known).targetPathOrNull())
            .isEqualTo("$ROOT/Android/data/com.gone")
        assertThat(MaintenanceRequest.RemoveUninstalledAppData(ExternalArea.OBB, "com.gone", fp, known).targetPathOrNull())
            .isEqualTo("$ROOT/Android/obb/com.gone")
    }

    @Test fun `package identifiers that could steer a path or a command have no target`() {
        val evil = listOf("../etc", "com.example/../../DCIM", "com.example; rm -rf /", "com.example && reboot", "my app", "", ".", "..", "a/b.c", "com.example\n", "\$(id).x.y", "com.exa`mple`.x")
        for (bad in evil) {
            assertThat(MaintenanceRequest.ClearExternalAppCache(bad, fp).targetPathOrNull()).isNull()
            assertThat(MaintenanceRequest.RemoveUninstalledAppData(ExternalArea.DATA, bad, fp, known).targetPathOrNull()).isNull()
        }
    }

    @Test fun `each request knows its operation and its delete mode`() {
        val cache = MaintenanceRequest.ClearExternalAppCache("com.example", fp)
        val tree = MaintenanceRequest.RemoveUninstalledAppData(ExternalArea.DATA, "com.gone", fp, known)
        assertThat(cache.operation).isEqualTo(PrivilegedOperation.CLEAR_EXTERNAL_APP_CACHE)
        assertThat(cache.deleteMode()).isEqualTo(DeleteMode.CONTENTS_ONLY)
        assertThat(tree.operation).isEqualTo(PrivilegedOperation.REMOVE_UNINSTALLED_APP_DATA)
        assertThat(tree.deleteMode()).isEqualTo(DeleteMode.TREE)
    }

    private fun cacheItem(path: String = "$ROOT/Android/data/com.example/cache", pkg: String = "com.example", fingerprint: FsStat? = fp) =
        scanItem(path, CleaningCategory.APP_CACHE, ConfidenceLevel.HIGH, RiskLevel.SAFE, pkg, shizuku = true, fingerprint = fingerprint)

    private fun corpseItem(path: String = "$ROOT/Android/data/com.gone", pkg: String = "com.gone", fingerprint: FsStat? = fp) =
        scanItem(path, CleaningCategory.CORPSE_LEFTOVER, pkg = pkg, shizuku = true, fingerprint = fingerprint)

    @Test fun `scan items map to the two supported requests`() {
        val cache = cacheItem().toMaintenanceRequest(known)
        assertThat(cache).isEqualTo(MaintenanceRequest.ClearExternalAppCache("com.example", fp))

        val data = corpseItem().toMaintenanceRequest(known) as MaintenanceRequest.RemoveUninstalledAppData
        assertThat(data.area).isEqualTo(ExternalArea.DATA)
        assertThat(data.knownPackages).isEqualTo(known)

        val obb = corpseItem("$ROOT/Android/obb/com.gone").toMaintenanceRequest(known) as MaintenanceRequest.RemoveUninstalledAppData
        assertThat(obb.area).isEqualTo(ExternalArea.OBB)
    }

    @Test fun `an item that is not exactly one of those shapes maps to nothing, so nothing weaker is run`() {
        assertThat(cacheItem(fingerprint = null).toMaintenanceRequest(known)).isNull()
        assertThat(cacheItem(pkg = "com.other").toMaintenanceRequest(known)).isNull() // folder belongs to another package
        assertThat(cacheItem(path = "$ROOT/Android/data/com.example/cache/sub").toMaintenanceRequest(known)).isNull()
        assertThat(cacheItem(path = "$ROOT/Android/data/com.example").toMaintenanceRequest(known)).isNull()
        assertThat(cacheItem(path = "$ROOT/android/DATA/com.example/cache").toMaintenanceRequest(known)).isNull() // not the canonical spelling
        assertThat(corpseItem(path = "$ROOT/Android/data/com.gone/files").toMaintenanceRequest(known)).isNull()
        assertThat(corpseItem(path = "$ROOT/Android/media/com.gone").toMaintenanceRequest(known)).isNull()
        assertThat(corpseItem(pkg = "com.not.matching").toMaintenanceRequest(known)).isNull()
        assertThat(corpseItem(path = "$ROOT/Android/data/com.gone/").toMaintenanceRequest(known)).isNull() // trailing slash
        assertThat(scanItem("$ROOT/Download/x.bin", shizuku = true, fingerprint = fp).toMaintenanceRequest(known)).isNull()
        assertThat(scanItem("$ROOT/Android/data/com.example/cache", CleaningCategory.LARGE_FILE, pkg = "com.example", shizuku = true, fingerprint = fp)
            .toMaintenanceRequest(known)).isNull()
    }

    // ------------------------------------------------------------------ classification

    private fun classify(mode: DeleteMode, status: DeleteStatus, post: PostState = PostState.Gone, entries: Int = 0, bytes: Long = 0, detail: String = "") =
        classifyDeletion(mode, DeleteResult(status, entries, bytes, detail), post)

    @Test fun `OK plus the expected end state is a verified success`() {
        val tree = classify(DeleteMode.TREE, DeleteStatus.OK, PostState.Gone, 3, 90)
        assertThat(tree).isEqualTo(MaintenanceResult.Success(3, 90))
        val cache = classify(DeleteMode.CONTENTS_ONLY, DeleteStatus.OK, PostState.ExistsEmpty, 2, 50)
        assertThat(cache).isEqualTo(MaintenanceResult.Success(2, 50))
        assertThat(classify(DeleteMode.FILE, DeleteStatus.OK, PostState.Gone, 1, 10)).isInstanceOf(MaintenanceResult.Success::class.java)
    }

    @Test fun `OK that the end state contradicts is Partial, never Success`() {
        assertThat(classify(DeleteMode.TREE, DeleteStatus.OK, PostState.ExistsNonEmpty("the folder is still present"), 2, 20))
            .isInstanceOf(MaintenanceResult.Partial::class.java)
        assertThat(classify(DeleteMode.TREE, DeleteStatus.OK, PostState.ExistsEmpty, 2, 20))
            .isInstanceOf(MaintenanceResult.Partial::class.java)
        val cache = classify(DeleteMode.CONTENTS_ONLY, DeleteStatus.OK, PostState.ExistsNonEmpty("3 entries remain"), 2, 20)
        assertThat(cache).isInstanceOf(MaintenanceResult.Partial::class.java)
        assertThat((cache as MaintenanceResult.Partial).bytesFreed).isEqualTo(20L)
    }

    @Test fun `OK that cannot be checked afterwards is Unverified and keeps the reported bytes`() {
        val r = classify(DeleteMode.TREE, DeleteStatus.OK, PostState.Unknown("service stopped"), 2, 20) as MaintenanceResult.Unverified
        assertThat(r.bytesFreed).isEqualTo(20L)
        assertThat(r.message).contains("service stopped")
    }

    @Test fun `a cache folder that vanished with its contents still counts as cleared`() {
        assertThat(classify(DeleteMode.CONTENTS_ONLY, DeleteStatus.OK, PostState.Gone, 1, 5)).isInstanceOf(MaintenanceResult.Success::class.java)
    }

    @Test fun `partial, limit and cancel results keep the work that was really done`() {
        val partial = classify(DeleteMode.CONTENTS_ONLY, DeleteStatus.PARTIAL, entries = 4, bytes = 400, detail = "Could not remove an entry") as MaintenanceResult.Partial
        assertThat(partial.bytesFreed).isEqualTo(400L)
        assertThat(partial.message).contains("Could not remove an entry")

        assertThat(classify(DeleteMode.TREE, DeleteStatus.LIMIT_EXCEEDED, entries = 9, bytes = 90)).isInstanceOf(MaintenanceResult.Partial::class.java)
        val limit0 = classify(DeleteMode.TREE, DeleteStatus.LIMIT_EXCEEDED) as MaintenanceResult.Failure
        assertThat(limit0.kind).isEqualTo(FailureKind.LIMIT)

        assertThat(classify(DeleteMode.CONTENTS_ONLY, DeleteStatus.CANCELLED, entries = 1, bytes = 10)).isInstanceOf(MaintenanceResult.Partial::class.java)
        val cancel0 = classify(DeleteMode.CONTENTS_ONLY, DeleteStatus.CANCELLED) as MaintenanceResult.Failure
        assertThat(cancel0.kind).isEqualTo(FailureKind.CANCELLED)
    }

    @Test fun `statuses that mean nothing was deleted become precondition, refusal or failure`() {
        for (s in listOf(DeleteStatus.MISSING, DeleteStatus.IDENTITY_CHANGED, DeleteStatus.WRONG_TYPE)) {
            assertThat((classify(DeleteMode.TREE, s) as MaintenanceResult.Failure).kind).isEqualTo(FailureKind.PRECONDITION_CHANGED)
        }
        assertThat((classify(DeleteMode.TREE, DeleteStatus.REFUSED) as MaintenanceResult.Failure).kind).isEqualTo(FailureKind.REFUSED)
        val failed = classify(DeleteMode.FILE, DeleteStatus.FAILED, detail = "Could not remove file") as MaintenanceResult.Failure
        assertThat(failed.kind).isEqualTo(FailureKind.IO_ERROR)
        assertThat(failed.message).isEqualTo("Could not remove file")
        assertThat((classify(DeleteMode.FILE, DeleteStatus.UNAVAILABLE) as MaintenanceResult.Failure).kind).isEqualTo(FailureKind.UNAVAILABLE)
    }

    @Test fun `a timeout is Unverified, never a failure that implies nothing happened`() {
        assertThat(classify(DeleteMode.TREE, DeleteStatus.TIMED_OUT)).isInstanceOf(MaintenanceResult.Unverified::class.java)
    }

    @Test fun `every delete status is classified`() {
        for (s in DeleteStatus.entries) {
            for (mode in DeleteMode.entries) {
                for (post in listOf(PostState.Gone, PostState.ExistsEmpty, PostState.ExistsNonEmpty("x"), PostState.Unknown("y"))) {
                    assertThat(classify(mode, s, post, 1, 1).message).isNotNull()
                }
            }
        }
    }

    // ------------------------------------------------------------------ observing the end state

    private suspend fun observe(
        mode: DeleteMode,
        stat: FsResult<FsStat>,
        list: FsResult<DirListing> = FsResult.Ok(DirListing(emptyList()))
    ) = observePostState("/x", mode, { stat }, { list })

    @Test fun `end state is read from stat and list without ever treating a failure as gone`() = runTest {
        assertThat(observe(DeleteMode.TREE, FsResult.NotFound)).isEqualTo(PostState.Gone)
        assertThat(observe(DeleteMode.TREE, FsResult.Ok(fp))).isInstanceOf(PostState.ExistsNonEmpty::class.java)
        assertThat(observe(DeleteMode.CONTENTS_ONLY, FsResult.Ok(fp))).isEqualTo(PostState.ExistsEmpty)
        assertThat(observe(DeleteMode.CONTENTS_ONLY, FsResult.Ok(fp), FsResult.Ok(DirListing(listOf("a", "b")))))
            .isEqualTo(PostState.ExistsNonEmpty("2 entries remain"))
        assertThat(observe(DeleteMode.CONTENTS_ONLY, FsResult.Ok(fp), FsResult.Ok(DirListing(listOf("a"), truncated = true))))
            .isEqualTo(PostState.ExistsNonEmpty("many entries remain"))
        assertThat(observe(DeleteMode.CONTENTS_ONLY, FsResult.Ok(fp), FsResult.NotFound)).isEqualTo(PostState.Gone)

        for (bad in listOf(FsResult.Unavailable("u"), FsResult.Denied("d"), FsResult.Failed("f"))) {
            assertThat(observe(DeleteMode.TREE, bad)).isInstanceOf(PostState.Unknown::class.java)
            assertThat(observe(DeleteMode.CONTENTS_ONLY, FsResult.Ok(fp), bad)).isInstanceOf(PostState.Unknown::class.java)
        }
    }
}
