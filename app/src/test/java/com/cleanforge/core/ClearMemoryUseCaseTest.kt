package com.cleanforge.core

import com.cleanforge.core.backend.InstallState
import com.cleanforge.core.backend.PackageInventory
import com.cleanforge.core.memory.BackgroundProcessKiller
import com.cleanforge.core.memory.MemoryInfoProvider
import com.cleanforge.core.memory.RamSnapshot
import com.cleanforge.domain.usecase.ClearMemoryUseCase
import com.cleanforge.testutil.FakeInventory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ClearMemoryUseCaseTest {

    /** Returns the given readings in order, then null (reading failed). */
    private class FakeProvider(vararg readings: RamSnapshot?) : MemoryInfoProvider {
        private val queue = ArrayDeque(readings.toList())
        override fun snapshot(): RamSnapshot? = queue.removeFirstOrNull()
    }

    private class RecordingKiller(
        private val throwFor: Set<String> = emptySet(),
        private val skip: Set<String> = emptySet()
    ) : BackgroundProcessKiller {
        val killed = mutableListOf<String>()
        override fun kill(packageName: String): Boolean {
            if (packageName in throwFor) throw SecurityException("denied")
            if (packageName in skip) return false
            killed += packageName
            return true
        }
    }

    /** Same as [FakeInventory] but one package's lookup fails (UNKNOWN). */
    private class FlakyInventory(private val base: FakeInventory, private val unknownFor: String) : PackageInventory by base {
        override fun installState(packageName: String): InstallState =
            if (packageName == unknownFor) InstallState.UNKNOWN else base.installState(packageName)
    }

    private fun snapshot(availableBytes: Long) = RamSnapshot(
        totalBytes = 4_000_000_000L,
        availableBytes = availableBytes,
        freeBytes = 0L, buffersBytes = 0L, cachedBytes = 0L,
        swapTotalBytes = 0L, swapFreeBytes = 0L,
        timestamp = 0L
    )

    private fun useCase(
        provider: MemoryInfoProvider,
        inventory: PackageInventory,
        killer: BackgroundProcessKiller
    ) = ClearMemoryUseCase(killer, inventory, provider).also { it.settleDelayMs = 0L }

    @Test fun `freed bytes are measured from the MemAvailable delta, never estimated`() = runTest {
        val killer = RecordingKiller()
        val result = useCase(
            FakeProvider(snapshot(1_000_000_000L), snapshot(1_500_000_000L)),
            FakeInventory(known = setOf("com.test.app")),
            killer
        )()

        assertThat(result.freedBytes).isEqualTo(500_000_000L)
        assertThat(result.measured).isTrue()
        assertThat(result.packagesRequested).isEqualTo(1)
        assertThat(killer.killed).containsExactly("com.test.app")
    }

    @Test fun `if available memory drops after the clear, freed bytes is zero and never negative`() = runTest {
        val result = useCase(
            FakeProvider(snapshot(1_000_000_000L), snapshot(900_000_000L)),
            FakeInventory(known = setOf("com.test.app")),
            RecordingKiller()
        )()

        assertThat(result.freedBytes).isEqualTo(0L)
        assertThat(result.measured).isTrue()
        assertThat(result.note).contains("No measurable gain")
    }

    @Test fun `an app uninstalled with its data kept is never touched`() = runTest {
        val killer = RecordingKiller()
        val result = useCase(
            FakeProvider(snapshot(1000L), snapshot(1000L)),
            FakeInventory(known = setOf("com.kept.app", "com.live.app"), installed = setOf("com.live.app")),
            killer
        )()

        assertThat(killer.killed).containsExactly("com.live.app")
        assertThat(result.packagesRequested).isEqualTo(1)
    }

    @Test fun `a package whose install state is UNKNOWN is never touched`() = runTest {
        val killer = RecordingKiller()
        val inventory = FlakyInventory(FakeInventory(known = setOf("com.unknown.app", "com.live.app")), "com.unknown.app")
        val result = useCase(FakeProvider(snapshot(1000L), snapshot(1000L)), inventory, killer)()

        assertThat(killer.killed).containsExactly("com.live.app")
        assertThat(result.packagesRequested).isEqualTo(1)
    }

    @Test fun `a failed package list touches nothing`() = runTest {
        val killer = RecordingKiller()
        val result = useCase(FakeProvider(snapshot(1000L), snapshot(1000L)), FakeInventory(known = null), killer)()

        assertThat(killer.killed).isEmpty()
        assertThat(result.packagesRequested).isEqualTo(0)
    }

    @Test fun `a refused package does not stop the others and is not counted`() = runTest {
        val killer = RecordingKiller(throwFor = setOf("com.protected.app"))
        val result = useCase(
            FakeProvider(snapshot(1000L), snapshot(1000L)),
            FakeInventory(known = setOf("com.protected.app", "com.ok.app")),
            killer
        )()

        assertThat(killer.killed).containsExactly("com.ok.app")
        assertThat(result.packagesRequested).isEqualTo(1)
    }

    @Test fun `apps the killer deliberately skips are not counted as requested`() = runTest {
        val killer = RecordingKiller(skip = setOf("com.system.like"))
        val result = useCase(
            FakeProvider(snapshot(1000L), snapshot(1000L)),
            FakeInventory(known = setOf("com.system.like", "com.user.app")),
            killer
        )()

        assertThat(result.packagesRequested).isEqualTo(1)
    }

    @Test fun `when memory cannot be read the result says unknown instead of claiming zero freed`() = runTest {
        val result = useCase(FakeProvider(null, null), FakeInventory(known = setOf("com.test.app")), RecordingKiller())()

        assertThat(result.measured).isFalse()
        assertThat(result.freedBytes).isEqualTo(0L)
        assertThat(result.note).contains("could not be read")
        assertThat(result.note).doesNotContain("No measurable gain")
    }
}
