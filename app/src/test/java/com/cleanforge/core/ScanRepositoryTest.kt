package com.cleanforge.core

import com.cleanforge.core.backend.Capabilities
import com.cleanforge.core.model.ScanItem
import com.cleanforge.core.privileged.OperationCapability
import com.cleanforge.core.scanner.Requirement
import com.cleanforge.core.scanner.ScanMode
import com.cleanforge.core.scanner.Scanner
import com.cleanforge.data.repository.ScanProgress
import com.cleanforge.data.repository.ScanRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
        override val privilegedOperations: List<OperationCapability> = emptyList(),
        private val items: List<ScanItem> = emptyList(),
        private val shouldThrow: Boolean = false,
        private val incomplete: List<String> = emptyList()
    ) : Scanner {
        override suspend fun scan(): Flow<ScanItem> = flow {
            if (shouldThrow) throw RuntimeException("Test exception")
            items.forEach { emit(it) }
        }

        override suspend fun incompleteReasons(): List<String> = incomplete
    }

    class FakeCapabilities : Capabilities {
        val askedOperations = mutableListOf<OperationCapability>()
        var hasAllFiles = true
        var shizukuAuth = true

        // Assuming OperationCapability has `canAttempt` and `reason` properties
        val readinessMap = mutableMapOf<OperationCapability, OperationCapability>()

        override suspend fun hasAllFilesAccess(): Boolean = hasAllFiles
        override suspend fun shizukuAuthorized(): Boolean = shizukuAuth

        override suspend fun readiness(op: OperationCapability): OperationCapability {
            askedOperations.add(op)
            // Default to a usable mock if not explicitly set in the test
            return readinessMap[op] ?: mock {
                on { canAttempt } doReturn true
                on { reason } doReturn null
            }
        }
    }

    private fun createScanItem(path: String, size: Long = 100L, categoryOrdinal: Int = 0): ScanItem {
        return mock {
            on { this.path } doReturn path
            on { this.sizeBytes } doReturn size
            on { this.category } doReturn mock {
                on { ordinal } doReturn categoryOrdinal
            }
        }
    }

    // --- Tests ---

    @Test
    fun `a complete scan announces nothing missing`() = runTest {
        val scanner = FakeScanner(id = "s1", requirement = Requirement.NONE, mode = ScanMode.FULL)
        val capabilities = FakeCapabilities()
        val repository = ScanRepository(setOf(scanner), capabilities)

        val progress = repository.scan(ScanMode.FULL).toList()

        assertThat(progress).hasSize(2)
        assertThat(progress[0]).isInstanceOf(ScanProgress.Started::class.java)
        assertThat(progress[1]).isInstanceOf(ScanProgress.Finished::class.java)
    }

    @Test
    fun `a Shizuku scanner that declares no operations still needs Shizuku to be authorized`() = runTest {
        val scanner = FakeScanner(id = "s1", requirement = Requirement.SHIZUKU, mode = ScanMode.FULL, privilegedOperations = emptyList())
        val capabilities = FakeCapabilities().apply {
            shizukuAuth = false
        }
        val repository = ScanRepository(setOf(scanner), capabilities)

        val progress = repository.scan(ScanMode.FULL).toList()

        assertThat(progress[1]).isInstanceOf(ScanProgress.ScannerSkipped::class.java)
        assertThat((progress[1] as ScanProgress.ScannerSkipped).reason).isEqualTo("needs Shizuku")
    }

    @Test
    fun `a connected service is not enough, every operation the scanner needs must be usable`() = runTest {
        val op1 = OperationCapability.LIST_EXTERNAL_APP_DATA
        val op2 = OperationCapability.MEASURE_EXTERNAL_APP_DIR
        val scanner = FakeScanner(id = "s1", requirement = Requirement.SHIZUKU, mode = ScanMode.FULL, privilegedOperations = listOf(op1, op2))
        val capabilities = FakeCapabilities().apply {
            shizukuAuth = true // Service is connected
            readinessMap[op1] = mock { on { canAttempt } doReturn true }
            readinessMap[op2] = mock { 
                on { canAttempt } doReturn false 
                on { reason } doReturn "Not usable"
            }
        }
        val repository = ScanRepository(setOf(scanner), capabilities)

        val progress = repository.scan(ScanMode.FULL).toList()

        assertThat(progress[1]).isInstanceOf(ScanProgress.ScannerSkipped::class.java)
        assertThat((progress[1] as ScanProgress.ScannerSkipped).reason).isEqualTo("Not usable")
    }

    @Test
    fun `a scanner that throws is reported as failed, its items are dropped, the others still run`() = runTest {
        val throwingScanner = FakeScanner(id = "s1", requirement = Requirement.NONE, mode = ScanMode.FULL, shouldThrow = true)
        val goodScanner = FakeScanner(id = "s2", requirement = Requirement.NONE, mode = ScanMode.FULL, items = listOf(createScanItem("a")))
        val repository = ScanRepository(setOf(throwingScanner, goodScanner), FakeCapabilities())

        val progress = repository.scan(ScanMode.FULL).toList()

        assertThat(progress.any { it is ScanProgress.ScannerFailed }).isTrue()
        assertThat(repository.items.value).hasSize(1)
    }

    @Test
    fun `a scanner whose operation is not usable is skipped with that operation's own reason`() = runTest {
        val op = OperationCapability.LIST_EXTERNAL_APP_DATA
        val scanner = FakeScanner(id = "s1", requirement = Requirement.SHIZUKU, mode = ScanMode.FULL, privilegedOperations = listOf(op))
        val capabilities = FakeCapabilities().apply {
            readinessMap[op] = mock { 
                on { canAttempt } doReturn false 
                on { reason } doReturn "Specific Reason" 
            }
        }
        val repository = ScanRepository(setOf(scanner), capabilities)

        val progress = repository.scan(ScanMode.FULL).toList()

        assertThat(progress[1]).isInstanceOf(ScanProgress.ScannerSkipped::class.java)
        assertThat((progress[1] as ScanProgress.ScannerSkipped).reason).isEqualTo("Specific Reason")
    }

    @Test
    fun `a scanner whose operations are usable runs and its items are published`() = runTest {
        val op = OperationCapability.LIST_EXTERNAL_APP_DATA
        val scanner = FakeScanner(id = "s1", requirement = Requirement.SHIZUKU, mode = ScanMode.FULL, privilegedOperations = listOf(op), items = listOf(createScanItem("a")))
        val capabilities = FakeCapabilities().apply {
            readinessMap[op] = mock { on { canAttempt } doReturn true }
        }
        val repository = ScanRepository(setOf(scanner), capabilities)

        repository.scan(ScanMode.FULL).toList()

        assertThat(repository.items.value).hasSize(1)
    }

    @Test
    fun `an incomplete result is announced next to the items it did find`() = runTest {
        val scanner = FakeScanner(id = "s1", requirement = Requirement.NONE, mode = ScanMode.FULL, items = listOf(createScanItem("a")), incomplete = listOf("Some reason"))
        val repository = ScanRepository(setOf(scanner), FakeCapabilities())

        val progress = repository.scan(ScanMode.FULL).toList()

        assertThat(progress.any { it is ScanProgress.ScannerIncomplete }).isTrue()
        assertThat(repository.items.value).hasSize(1)
    }

    @Test
    fun `asking stops at the first operation that is not usable`() = runTest {
        val op1 = OperationCapability.LIST_EXTERNAL_APP_DATA
        val op2 = OperationCapability.MEASURE_EXTERNAL_APP_DIR
        val scanner = FakeScanner(
            id = "test-scanner",
            requirement = Requirement.SHIZUKU,
            mode = ScanMode.FULL,
            privilegedOperations = listOf(op1, op2)
        )
        val capabilities = FakeCapabilities().apply {
            // FIX: The first operation MUST be set to NOT usable to stop the loop immediately.
            readinessMap[op1] = mock { 
                on { canAttempt } doReturn false 
                on { reason } doReturn "First op failed" 
            }
            // op2 is never checked because the loop breaks at op1.
        }
        val repository = ScanRepository(setOf(scanner), capabilities)

        repository.scan(ScanMode.FULL).toList()

        // Verify that the repository ONLY asked about op1 and stopped.
        // It should NOT contain op2.
        assertThat(capabilities.askedOperations).containsExactly(op1)
    }

    @Test
    fun `forget removes cleaned items without a rescan`() = runTest {
        val scanner = FakeScanner(id = "s1", requirement = Requirement.NONE, mode = ScanMode.FULL, items = listOf(createScanItem("a"), createScanItem("b")))
        val repository = ScanRepository(setOf(scanner), FakeCapabilities())
        repository.scan(ScanMode.FULL).toList()

        assertThat(repository.items.value).hasSize(2)

        repository.forget(setOf("a"))

        assertThat(repository.items.value).hasSize(1)
        assertThat(repository.items.value.first().path).isEqualTo("b")
    }
}
