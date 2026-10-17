package com.cleanforge.core

import com.cleanforge.core.backend.Capabilities
import com.cleanforge.core.model.CleaningCategory
import com.cleanforge.core.model.ConfidenceLevel
import com.cleanforge.core.model.RiskLevel
import com.cleanforge.core.model.ScanItem
import com.cleanforge.core.privileged.CapabilityStatus
import com.cleanforge.core.privileged.OperationCapability
import com.cleanforge.core.privileged.PrivilegedOperation
import com.cleanforge.core.scanner.Requirement
import com.cleanforge.core.scanner.ScanMode
import com.cleanforge.core.scanner.Scanner
import com.cleanforge.data.repository.ScanProgress
import com.cleanforge.data.repository.ScanRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ScanRepositoryTest {

    // --- Fakes ---

    class FakeScanner(
        override val id: String,
        override val requirement: Requirement,
        override val mode: ScanMode,
        override val displayName: String = "Scanner $id",
        override val privilegedOperations: Set<PrivilegedOperation> = emptySet(),
        private val items: List<ScanItem> = emptyList(),
        private val shouldThrow: Boolean = false,
        private val incomplete: List<String> = emptyList()
    ) : Scanner {
        // Matches Scanner.kt: fun scan(): Flow<List<ScanItem>>
        override fun scan(): Flow<List<ScanItem>> {
            if (shouldThrow) return flow { throw RuntimeException("Test exception") }
            return flowOf(items)
        }

        // Matches Scanner.kt: fun incompleteReasons(): List<String> (NOT suspend)
        override fun incompleteReasons(): List<String> = incomplete
    }

    class FakeCapabilities : Capabilities {
        val askedOperations = mutableListOf<PrivilegedOperation>()
        var hasAllFiles = true
        var shizukuAuth = true

        val readinessMap = mutableMapOf<PrivilegedOperation, OperationCapability>()

        // Matches Capabilities.kt: fun hasAllFilesAccess(): Boolean (NOT suspend)
        override fun hasAllFilesAccess(): Boolean = hasAllFiles
        
        // Matches Capabilities.kt: fun shizukuAuthorized(): Boolean (NOT suspend)
        override fun shizukuAuthorized(): Boolean = shizukuAuth

        // Matches Capabilities.kt: suspend fun readiness(op: PrivilegedOperation): OperationCapability
        override suspend fun readiness(op: PrivilegedOperation): OperationCapability {
            askedOperations.add(op)
            return readinessMap[op] ?: OperationCapability(
                operation = op,
                status = CapabilityStatus.VERIFIED,
                reason = "Default usable"
            )
        }
    }

    // NO MOCKITO. Direct instantiation using the defaults from your ScanItem.kt
    private fun createScanItem(path: String): ScanItem {
        return ScanItem(
            path = path,
            sizeBytes = 100L,
            category = CleaningCategory.APP_CACHE,
            reasonForFlagging = "Test reason",
            confidence = ConfidenceLevel.HIGH,
            risk = RiskLevel.SAFE
        )
    }

    // --- Tests ---

    @Test
    fun `a complete scan announces nothing missing`() = runTest {
        val scanner = FakeScanner(id = "s1", requirement = Requirement.NONE, mode = ScanMode.QUICK)
        val repository = ScanRepository(setOf(scanner), FakeCapabilities())

        val progress = repository.scan(ScanMode.QUICK).toList()

        // A successful scan emits 3 events: Started, ScannerStarted, Finished
        assertThat(progress).hasSize(3)
        assertThat(progress[0]).isInstanceOf(ScanProgress.Started::class.java)
        assertThat(progress[1]).isInstanceOf(ScanProgress.ScannerStarted::class.java)
        assertThat(progress[2]).isInstanceOf(ScanProgress.Finished::class.java)
    }

    @Test
    fun `a Shizuku scanner that declares no operations still needs Shizuku to be authorized`() = runTest {
        val scanner = FakeScanner(id = "s1", requirement = Requirement.SHIZUKU, mode = ScanMode.QUICK, privilegedOperations = emptySet())
        val capabilities = FakeCapabilities().apply { shizukuAuth = false }
        val repository = ScanRepository(setOf(scanner), capabilities)

        val progress = repository.scan(ScanMode.QUICK).toList()

        // Emits: Started, ScannerSkipped, Finished
        assertThat(progress[1]).isInstanceOf(ScanProgress.ScannerSkipped::class.java)
        assertThat((progress[1] as ScanProgress.ScannerSkipped).reason).isEqualTo("needs Shizuku")
    }

    @Test
    fun `a connected service is not enough, every operation the scanner needs must be usable`() = runTest {
        val op1 = PrivilegedOperation.LIST_EXTERNAL_APP_DATA
        val op2 = PrivilegedOperation.MEASURE_EXTERNAL_APP_DIR
        val scanner = FakeScanner(id = "s1", requirement = Requirement.SHIZUKU, mode = ScanMode.QUICK, privilegedOperations = setOf(op1, op2))
        
        val capabilities = FakeCapabilities().apply {
            shizukuAuth = true 
            readinessMap[op1] = OperationCapability(op1, CapabilityStatus.VERIFIED, "Usable")
            readinessMap[op2] = OperationCapability(op2, CapabilityStatus.UNAVAILABLE, "Not usable")
        }
        val repository = ScanRepository(setOf(scanner), capabilities)

        val progress = repository.scan(ScanMode.QUICK).toList()

        assertThat(progress[1]).isInstanceOf(ScanProgress.ScannerSkipped::class.java)
        assertThat((progress[1] as ScanProgress.ScannerSkipped).reason).isEqualTo("Not usable")
    }

    @Test
    fun `a scanner that throws is reported as failed, its items are dropped, the others still run`() = runTest {
        val throwingScanner = FakeScanner(id = "s1", requirement = Requirement.NONE, mode = ScanMode.QUICK, shouldThrow = true)
        val goodScanner = FakeScanner(id = "s2", requirement = Requirement.NONE, mode = ScanMode.QUICK, items = listOf(createScanItem("a")))
        val repository = ScanRepository(setOf(throwingScanner, goodScanner), FakeCapabilities())

        val progress = repository.scan(ScanMode.QUICK).toList()

        assertThat(progress.any { it is ScanProgress.ScannerFailed }).isTrue()
        assertThat(repository.items.value).hasSize(1)
    }

    @Test
    fun `a scanner whose operation is not usable is skipped with that operation's own reason`() = runTest {
        val op = PrivilegedOperation.LIST_EXTERNAL_APP_DATA
        val scanner = FakeScanner(id = "s1", requirement = Requirement.SHIZUKU, mode = ScanMode.QUICK, privilegedOperations = setOf(op))
        val capabilities = FakeCapabilities().apply {
            readinessMap[op] = OperationCapability(op, CapabilityStatus.UNAVAILABLE, "Specific Reason")
        }
        val repository = ScanRepository(setOf(scanner), capabilities)

        val progress = repository.scan(ScanMode.QUICK).toList()

        assertThat(progress[1]).isInstanceOf(ScanProgress.ScannerSkipped::class.java)
        assertThat((progress[1] as ScanProgress.ScannerSkipped).reason).isEqualTo("Specific Reason")
    }

    @Test
    fun `a scanner whose operations are usable runs and its items are published`() = runTest {
        val op = PrivilegedOperation.LIST_EXTERNAL_APP_DATA
        val scanner = FakeScanner(id = "s1", requirement = Requirement.SHIZUKU, mode = ScanMode.QUICK, privilegedOperations = setOf(op), items = listOf(createScanItem("a")))
        val capabilities = FakeCapabilities().apply {
            readinessMap[op] = OperationCapability(op, CapabilityStatus.VERIFIED, "Usable")
        }
        val repository = ScanRepository(setOf(scanner), capabilities)

        repository.scan(ScanMode.QUICK).toList()

        assertThat(repository.items.value).hasSize(1)
    }

    @Test
    fun `an incomplete result is announced next to the items it did find`() = runTest {
        val scanner = FakeScanner(id = "s1", requirement = Requirement.NONE, mode = ScanMode.QUICK, items = listOf(createScanItem("a")), incomplete = listOf("Some reason"))
        val repository = ScanRepository(setOf(scanner), FakeCapabilities())

        val progress = repository.scan(ScanMode.QUICK).toList()

        assertThat(progress.any { it is ScanProgress.ScannerIncomplete }).isTrue()
        assertThat(repository.items.value).hasSize(1)
    }

    @Test
    fun `asking stops at the first operation that is not usable`() = runTest {
        val op1 = PrivilegedOperation.LIST_EXTERNAL_APP_DATA
        val op2 = PrivilegedOperation.MEASURE_EXTERNAL_APP_DIR
        
        // Set preserves insertion order, so op1 is strictly checked first
        val scanner = FakeScanner(
            id = "test-scanner",
            requirement = Requirement.SHIZUKU,
            mode = ScanMode.QUICK,
            privilegedOperations = setOf(op1, op2) 
        )
        val capabilities = FakeCapabilities().apply {
            // op1 is UNAVAILABLE, so the repository's loop will break immediately.
            readinessMap[op1] = OperationCapability(op1, CapabilityStatus.UNAVAILABLE, "First op failed")
        }
        val repository = ScanRepository(setOf(scanner), capabilities)

        repository.scan(ScanMode.QUICK).toList()

        // Verify that ONLY op1 was asked about. op2 is never reached.
        assertThat(capabilities.askedOperations).containsExactly(op1)
    }

    @Test
    fun `forget removes cleaned items without a rescan`() = runTest {
        val scanner = FakeScanner(id = "s1", requirement = Requirement.NONE, mode = ScanMode.QUICK, items = listOf(createScanItem("a"), createScanItem("b")))
        val repository = ScanRepository(setOf(scanner), FakeCapabilities())
        repository.scan(ScanMode.QUICK).toList()

        assertThat(repository.items.value).hasSize(2)

        repository.forget(setOf("a"))

        assertThat(repository.items.value).hasSize(1)
        assertThat(repository.items.value.first().path).isEqualTo("b")
    }
}
