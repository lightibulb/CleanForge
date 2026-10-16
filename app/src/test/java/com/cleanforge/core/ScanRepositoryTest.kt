package com.cleanforge.core

import com.cleanforge.core.backend.Capabilities
import com.cleanforge.core.model.ScanItem
import com.cleanforge.core.privileged.CapabilityStatus
import com.cleanforge.core.privileged.OperationCapability
import com.cleanforge.core.privileged.PrivilegedOperation
import com.cleanforge.core.scanner.Requirement
import com.cleanforge.core.scanner.ScanMode
import com.cleanforge.core.scanner.Scanner
import com.cleanforge.data.repository.ScanProgress
import com.cleanforge.data.repository.ScanRepository
import com.cleanforge.testutil.FakeCapabilities
import com.cleanforge.testutil.ROOT
import com.cleanforge.testutil.scanItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ScanRepositoryTest {

    private class FakeScanner(
        override val id: String,
        override val requirement: Requirement = Requirement.NONE,
        override val privilegedOperations: Set<PrivilegedOperation> = emptySet(),
        private val items: List<ScanItem> = emptyList(),
        private val reasons: List<String> = emptyList(),
        private val failWith: Throwable? = null
    ) : Scanner {
        override val displayName: String = "Scanner $id"
        override val mode: ScanMode = ScanMode.QUICK
        override fun scan(): Flow<List<ScanItem>> = flow {
            failWith?.let { throw it }
            emit(items)
        }

        override fun incompleteReasons(): List<String> = reasons
    }

    private val reads = linkedSetOf(PrivilegedOperation.LIST_EXTERNAL_APP_DATA, PrivilegedOperation.MEASURE_EXTERNAL_APP_DIR)
    private fun item(name: String) = scanItem("$ROOT/Download/$name")

    private fun caps(decide: (PrivilegedOperation) -> OperationCapability): Capabilities = object : Capabilities {
        override fun hasAllFilesAccess() = true
        override fun shizukuAuthorized() = true
        override suspend fun readiness(op: PrivilegedOperation): OperationCapability = decide(op)
    }

    private fun capability(op: PrivilegedOperation, status: CapabilityStatus, reason: String = "reason for $op") =
        OperationCapability(op, status, reason)

    @Test fun `a scanner whose operation is not usable is skipped with that operation's own reason`() = runTest {
        val scanner = FakeScanner("shizuku", Requirement.SHIZUKU, reads, listOf(item("a.bin")))
        val repo = ScanRepository(
            setOf(scanner),
            caps { op -> capability(op, CapabilityStatus.NEEDS_AUTHORIZATION, "Allow CleanForge in Shizuku") }
        )
        val progress = repo.scan(ScanMode.QUICK).toList()
        val skipped = progress.filterIsInstance<ScanProgress.ScannerSkipped>().single()
        assertThat(skipped.reason).isEqualTo("Allow CleanForge in Shizuku")
        assertThat(repo.items.value).isEmpty()
    }

    @Test fun `a connected service is not enough, every operation the scanner needs must be usable`() = runTest {
        val scanner = FakeScanner("shizuku", Requirement.SHIZUKU, reads, listOf(item("a.bin")))
        val repo = ScanRepository(
            setOf(scanner),
            caps { op ->
                if (op == PrivilegedOperation.MEASURE_EXTERNAL_APP_DIR) {
                    capability(op, CapabilityStatus.UNSUPPORTED, "Measuring is not possible here")
                } else {
                    capability(op, CapabilityStatus.VERIFIED)
                }
            }
        )
        val skipped = repo.scan(ScanMode.QUICK).toList().filterIsInstance<ScanProgress.ScannerSkipped>().single()
        assertThat(skipped.reason).isEqualTo("Measuring is not possible here")
        assertThat(repo.items.value).isEmpty()
    }

    @Test fun `a scanner whose operations are usable runs and its items are published`() = runTest {
        val scanner = FakeScanner("shizuku", Requirement.SHIZUKU, reads, listOf(item("a.bin")))
        val repo = ScanRepository(setOf(scanner), caps { op -> capability(op, CapabilityStatus.PREREQUISITES_MET) })
        val progress = repo.scan(ScanMode.QUICK).toList()
        assertThat(progress.filterIsInstance<ScanProgress.ScannerSkipped>()).isEmpty()
        assertThat(repo.items.value.map { it.path }).containsExactly("$ROOT/Download/a.bin")
    }

    @Test fun `a Shizuku scanner that declares no operations still needs Shizuku to be authorized`() = runTest {
        val scanner = FakeScanner("legacy", Requirement.SHIZUKU, emptySet(), listOf(item("a.bin")))
        val off = ScanRepository(setOf(scanner), FakeCapabilities(shizuku = false))
        val skipped = off.scan(ScanMode.QUICK).toList().filterIsInstance<ScanProgress.ScannerSkipped>().single()
        assertThat(skipped.reason).isEqualTo("needs Shizuku")
        val on = ScanRepository(setOf(scanner), FakeCapabilities(shizuku = true))
        on.scan(ScanMode.QUICK).toList()
        assertThat(on.items.value).hasSize(1)
    }

    @Test fun `an incomplete result is announced next to the items it did find`() = runTest {
        val scanner = FakeScanner("cache", items = listOf(item("a.bin")), reasons = listOf("3 cache folder(s) could not be measured"))
        val repo = ScanRepository(setOf(scanner), FakeCapabilities())
        val progress = repo.scan(ScanMode.QUICK).toList()
        val incomplete = progress.filterIsInstance<ScanProgress.ScannerIncomplete>().single()
        assertThat(incomplete.name).isEqualTo("Scanner cache")
        assertThat(incomplete.reasons).containsExactly("3 cache folder(s) could not be measured")
        assertThat(repo.items.value).hasSize(1)
        assertThat(progress.last()).isInstanceOf(ScanProgress.Finished::class.java)
    }

    @Test fun `a complete scan announces nothing missing`() = runTest {
        val repo = ScanRepository(setOf(FakeScanner("ok", items = listOf(item("a.bin")))), FakeCapabilities())
        assertThat(repo.scan(ScanMode.QUICK).toList().filterIsInstance<ScanProgress.ScannerIncomplete>()).isEmpty()
    }

    @Test fun `a scanner that throws is reported as failed, its items are dropped, the others still run`() = runTest {
        val broken = FakeScanner("broken", failWith = IllegalStateException("boom"))
        val fine = FakeScanner("fine", items = listOf(item("b.bin")))
        val repo = ScanRepository(linkedSetOf<Scanner>(broken, fine), FakeCapabilities())
        val progress = repo.scan(ScanMode.QUICK).toList()
        val failed = progress.filterIsInstance<ScanProgress.ScannerFailed>().single()
        assertThat(failed.reason).contains("boom")
        assertThat(repo.items.value.map { it.path }).containsExactly("$ROOT/Download/b.bin")
    }

    @Test fun `forget removes cleaned items without a rescan`() = runTest {
        val repo = ScanRepository(setOf(FakeScanner("ok", items = listOf(item("a.bin"), item("b.bin")))), FakeCapabilities())
        repo.scan(ScanMode.QUICK).toList()
        repo.forget(setOf("$ROOT/Download/a.bin"))
        assertThat(repo.items.value.map { it.path }).containsExactly("$ROOT/Download/b.bin")
    }

    @Test fun `asking stops at the first operation that is not usable`() = runTest {
        val asked = mutableListOf<PrivilegedOperation>()
        val scanner = FakeScanner("shizuku", Requirement.SHIZUKU, reads, listOf(item("a.bin")))
        val repo = ScanRepository(
            setOf(scanner),
            caps { op ->
                asked += op
                capability(op, CapabilityStatus.UNSUPPORTED, "not here")
            }
        )
        repo.scan(ScanMode.QUICK).toList()
        assertThat(asked).containsExactly(reads.first())
    }
}
