package com.cleanforge.core

import com.cleanforge.core.backend.InstallState
import com.cleanforge.core.backend.PackageInventory
import com.cleanforge.core.memory.BackgroundProcessKiller
import com.cleanforge.core.memory.MemoryInfoProvider
import com.cleanforge.core.memory.RamSnapshot
import com.cleanforge.domain.usecase.ClearMemoryUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class ClearMemoryUseCaseTest {

    private fun snapshot(availableBytes: Long) = RamSnapshot(
        totalBytes = 4_000_000_000L,
        availableBytes = availableBytes,
        freeBytes = 0L,
        buffersBytes = 0L,
        cachedBytes = 0L,
        swapTotalBytes = 0L,
        swapFreeBytes = 0L,
        timestamp = 0L
    )

    /**
     * Hand-written test double for [BackgroundProcessKiller].
     * It records what the use case asked for and lets each test decide which packages are allowed.
     */
    private class RecordingBackgroundProcessKiller(
        private val allow: (String) -> Boolean
    ) : BackgroundProcessKiller {

        val killRequestsSent = mutableListOf<String>()
        val deliberatelySkipped = mutableListOf<String>()

        override fun kill(packageName: String): Boolean {
            return if (allow(packageName)) {
                killRequestsSent += packageName
                true
            } else {
                deliberatelySkipped += packageName
                false
            }
        }
    }

    private class ScriptedMemoryProvider(
        vararg snapshots: RamSnapshot?
    ) : MemoryInfoProvider {

        private val queue = ArrayDeque<RamSnapshot?>(snapshots.toList())

        override fun snapshot(): RamSnapshot? = queue.removeFirstOrNull()
    }

    /**
     * Proxy-based fake for [PackageInventory].
     *
     * This avoids requiring Mockito and avoids depending on the full shape of PackageInventory.
     * It handles the members used by ClearMemoryUseCase and common Object methods.
     */
    private fun fakeInventory(
        known: Set<String>?,
        installed: Set<String>,
        unknown: Set<String> = emptySet()
    ): PackageInventory {
        return Proxy.newProxyInstance(
            PackageInventory::class.java.classLoader,
            arrayOf(PackageInventory::class.java)
        ) { _, method, args ->
            val pkg = args?.firstOrNull() as? String

            when (method.name) {
                "knownPackageNames" -> known

                "isCurrentlyInstalled" -> pkg != null && pkg in installed

                "installState" -> when {
                    pkg == null -> InstallState.NOT_KNOWN
                    pkg in installed -> InstallState.INSTALLED
                    pkg in unknown -> InstallState.UNKNOWN
                    else -> InstallState.NOT_KNOWN
                }

                "toString" -> "FakePackageInventory"
                "hashCode" -> 0
                "equals" -> false

                else -> throw UnsupportedOperationException(
                    "Unexpected PackageInventory call in ClearMemoryUseCaseTest: ${method.name}"
                )
            }
        } as PackageInventory
    }

    @Test
    fun `freed bytes are measured honestly from MemAvailable delta, never estimated`() = runTest {
        val killer = RecordingBackgroundProcessKiller { true }
        val inventory = fakeInventory(
            known = setOf("com.test.app"),
            installed = setOf("com.test.app")
        )
        val provider = ScriptedMemoryProvider(
            snapshot(availableBytes = 1_000_000_000L),
            snapshot(availableBytes = 1_500_000_000L)
        )

        val useCase = ClearMemoryUseCase(killer, inventory, provider)
        val result = useCase()

        assertEquals(500_000_000L, result.freedBytes)
        assertEquals(1, result.processesTouched)
        assertTrue(killer.killRequestsSent.contains("com.test.app"))
        assertTrue(result.note.contains("Freed", ignoreCase = true))
    }

    @Test
    fun `if memory available drops after clear, freed bytes is zero, never negative`() = runTest {
        val killer = RecordingBackgroundProcessKiller { true }
        val inventory = fakeInventory(
            known = setOf("com.test.app"),
            installed = setOf("com.test.app")
        )
        val provider = ScriptedMemoryProvider(
            snapshot(availableBytes = 1_000_000_000L),
            snapshot(availableBytes = 900_000_000L)
        )

        val useCase = ClearMemoryUseCase(killer, inventory, provider)
        val result = useCase()

        assertEquals(0L, result.freedBytes)
        assertEquals(1, result.processesTouched)
    }

    @Test
    fun `packages skipped by BackgroundProcessKiller are not counted as touched`() = runTest {
        val killer = RecordingBackgroundProcessKiller { false }
        val inventory = fakeInventory(
            known = setOf("com.system.app"),
            installed = setOf("com.system.app")
        )
        val provider = ScriptedMemoryProvider(
            snapshot(availableBytes = 1_000_000_000L),
            snapshot(availableBytes = 1_000_000_000L)
        )

        val useCase = ClearMemoryUseCase(killer, inventory, provider)
        val result = useCase()

        assertEquals(0, result.processesTouched)
        assertTrue(killer.killRequestsSent.isEmpty())
        assertTrue(killer.deliberatelySkipped.contains("com.system.app"))
    }

    @Test
    fun `packages not currently installed are never passed to the killer`() = runTest {
        val killer = RecordingBackgroundProcessKiller { true }
        val inventory = fakeInventory(
            known = setOf("com.not.installed.app"),
            installed = emptySet()
        )
        val provider = ScriptedMemoryProvider(
            snapshot(availableBytes = 1_000_000_000L),
            snapshot(availableBytes = 1_000_000_000L)
        )

        val useCase = ClearMemoryUseCase(killer, inventory, provider)
        val result = useCase()

        assertEquals(0, result.processesTouched)
        assertTrue(killer.killRequestsSent.isEmpty())
    }

    @Test
    fun `packages whose install state cannot be determined are never killed`() = runTest {
        val killer = RecordingBackgroundProcessKiller { true }
        val inventory = fakeInventory(
            known = setOf("com.unknown.app"),
            installed = emptySet(),
            unknown = setOf("com.unknown.app")
        )
        val provider = ScriptedMemoryProvider(
            snapshot(availableBytes = 1_000_000_000L),
            snapshot(availableBytes = 1_000_000_000L)
        )

        val useCase = ClearMemoryUseCase(killer, inventory, provider)
        val result = useCase()

        assertEquals(0, result.processesTouched)
        assertTrue(killer.killRequestsSent.isEmpty())
    }

    @Test
    fun `if memory info is unavailable, result says so instead of inventing a number`() = runTest {
        val killer = RecordingBackgroundProcessKiller { true }
        val inventory = fakeInventory(
            known = setOf("com.test.app"),
            installed = setOf("com.test.app")
        )
        val provider = ScriptedMemoryProvider(null, null)

        val useCase = ClearMemoryUseCase(killer, inventory, provider)
        val result = useCase()

        assertEquals(0L, result.freedBytes)
        assertTrue(result.note.contains("unavailable", ignoreCase = true))
    }
}