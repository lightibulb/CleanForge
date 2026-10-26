package com.cleanforge.core.cleaning

import com.cleanforge.core.backend.Capabilities
import com.cleanforge.core.model.CleaningCategory
import com.cleanforge.core.model.CleaningPlan
import com.cleanforge.core.model.PermissionNeed
import com.cleanforge.core.privileged.CapabilityStatus
import com.cleanforge.core.privileged.OperationCapability
import com.cleanforge.core.privileged.PrivilegedOperation
import com.cleanforge.testutil.ROOT
import com.cleanforge.testutil.scanItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PermissionAnnotatorTest {

    private fun caps(allFiles: Boolean, shizuku: Boolean, ready: Boolean = true) = object : Capabilities {
        override fun hasAllFilesAccess() = allFiles
        override fun shizukuAuthorized() = shizuku
        override suspend fun readiness(op: PrivilegedOperation) =
            OperationCapability(op, if (ready) CapabilityStatus.PREREQUISITES_MET else CapabilityStatus.UNAVAILABLE,
                if (ready) "ok" else "service stopped")
    }

    private val cache = scanItem("$ROOT/Android/data/com.x/cache", CleaningCategory.APP_CACHE, pkg = "com.x", shizuku = true)
    private val download = scanItem("$ROOT/Download/a.bin")

    @Test fun `standard items need all-files, shizuku items need their operation`() = runTest {
        val plan = CleaningPlan(listOf(cache, download), emptyList())
        val a = PermissionAnnotator(caps(allFiles = true, shizuku = true)).annotate(plan)
        assertThat(a.permissionNotes[download.path]!!.need).isEqualTo(PermissionNeed.ALL_FILES)
        assertThat(a.permissionNotes[download.path]!!.blocking).isFalse()
        assertThat(a.permissionNotes[cache.path]!!.need).isEqualTo(PermissionNeed.SHIZUKU_OPERATION)
        assertThat(a.permissionNotes[cache.path]!!.blocking).isFalse()
        assertThat(a.hasBlockingPermissions).isFalse()
    }

    @Test fun `missing all-files marks standard items as blocking`() = runTest {
        val plan = CleaningPlan(listOf(download), emptyList())
        val a = PermissionAnnotator(caps(allFiles = false, shizuku = true)).annotate(plan)
        assertThat(a.permissionNotes[download.path]!!.blocking).isTrue()
        assertThat(a.hasBlockingPermissions).isTrue()
        assertThat(a.allItemsBlocked).isTrue()
    }

    @Test fun `a shizuku operation that cannot run blocks only that item`() = runTest {
        val plan = CleaningPlan(listOf(cache, download), emptyList())
        val a = PermissionAnnotator(caps(allFiles = true, shizuku = true, ready = false)).annotate(plan)
        assertThat(a.permissionNotes[cache.path]!!.blocking).isTrue()
        assertThat(a.permissionNotes[download.path]!!.blocking).isFalse()
        assertThat(a.allItemsBlocked).isFalse()
    }
}
