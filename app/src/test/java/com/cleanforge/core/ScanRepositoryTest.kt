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
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

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
        // FIX: Scanner.kt defines this as Flow<List<ScanItem>>
        override fun scan(): Flow<List<ScanItem>> {
            if (shouldThrow) throw RuntimeException("Test exception")
            return flowOf(items)
        }

        override fun incompleteReasons(): List<String> = incomplete
    }

    class FakeCapabilities : Capabilities {
        val askedOperations = mutableListOf<PrivilegedOperation>()
        var hasAllFiles = true
        var shizukuAuth = true

        // Map to hold the exact status we want to return for each operation
        val readinessMap = mutableMapOf<PrivilegedOperation, OperationCapability>()

        override fun hasAllFilesAccess(): Boolean = hasAllFiles
        override fun shizukuAuthorized(): Boolean = shizukuAuth

        override suspend fun readiness(op: PrivilegedOperation): OperationCapability {
            askedOperations.add(op)
            // Return the mapped value or default to a usable mock
            return readinessMap[op] ?: OperationCapability(
                operation = op,
                status = CapabilityStatus.VERIFIED,
                reason = "Default usable"
            )
        }
    }

    private fun createScanItem(path: String): ScanItem {
        // Using Mockito here since ScanItem relies on Enums we didn't see (Category, Confidence, Risk)
        return mock {
            on { this.path } doReturn path
            on { this.sizeBytes } doReturn 100L
            on { this.category } doReturn mock {
                on { this.ordinal } doReturn 0
            }
        }
    }

    // --- Tests ---

    @Test
    fun `a complete scan announces nothing missing`() = runTest {
        val scanner = FakeScanner(id = "s1", requirement = Requirement.NONE, mode = ScanMode.QUICK)
        val repository = ScanRepository(setOf(scanner), FakeCapabilities())

        val progress = repository.scan(ScanMode.QUICK).toList()

        assertThat(progress).hasSize(2)
        assertThat(progress[0]).isInstanceOf(ScanProgress.Started::class.java)
        assertThat(progress[1]).isInstanceOf(ScanProgress.Finished::class.java)
    }

    @Test
    fun `a Shizuku scanner that declares no operations still needs Shizuku to be authorized`() = runTest {
        val scanner = FakeScanner(id = "s1", requirement = Requirement.SHIZUKU, mode = ScanMode.QUICK, privilegedOperations = emptySet())
        val capabilities = FakeCapabilities().apply { shizukuAuth = false }
        val repository = ScanRepository(setOf(scanner), capabilities)

        val progress = repository.scan(ScanMode.QUICK).toList()

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
        val scanner = FakeScanner(
            id = "test-scanner",
            requirement = Requirement.SHIZUKU,
            mode = ScanMode.QUICK,
            privilegedOperations = setOf(op1, op2) // Both provided
        )
        val capabilities = FakeCapabilities().apply {
            // FIX: The FIRST operation MUST be set to NOT usable to stop the loop immediately.
            // Previously, op1 was usable, so the repository correctly went to check op2.
            readinessMap[op1] = OperationCapability(op1, CapabilityStatus.UNAVAILABLE, "First op failed")
        }
        val repository = ScanRepository(setOf(scanner), capabilities)

        repository.scan(ScanMode.QUICK).toList()

        // Verify that the repository ONLY asked about op1 and stopped. op2 is never asked about.
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
