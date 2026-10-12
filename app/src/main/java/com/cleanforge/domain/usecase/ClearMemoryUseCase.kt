package com.cleanforge.domain.usecase

import com.cleanforge.core.backend.InstallState
import com.cleanforge.core.backend.PackageInventory
import com.cleanforge.core.memory.BackgroundProcessKiller
import com.cleanforge.core.memory.ClearMemoryResult
import com.cleanforge.core.memory.MemoryInfoProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The real, limited, honest RAM-clear. It asks Android to stop BACKGROUND processes of apps that are installed right
 * now (tri-state [InstallState]: an UNKNOWN answer is never treated as installed), then MEASURES the change in
 * available memory instead of inventing a number.
 */
class ClearMemoryUseCase @Inject constructor(
    private val killer: BackgroundProcessKiller,
    private val inventory: PackageInventory,
    private val provider: MemoryInfoProvider
) {
    /** How long the kernel gets to reclaim pages before the second reading. Tests set it to 0. */
    internal var settleDelayMs: Long = 600L

    suspend operator fun invoke(): ClearMemoryResult = withContext(Dispatchers.IO) {
        val before = provider.snapshot()

        var requested = 0
        val known = inventory.knownPackageNames()
        if (known != null) {
            for (pkg in known) {
                ensureActive()
                if (inventory.installState(pkg) != InstallState.INSTALLED) continue
                try {
                    if (killer.kill(pkg)) requested++
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    // Per-package failures are expected (security exception, protected app). Skip it.
                }
            }
        }

        if (settleDelayMs > 0) delay(settleDelayMs)
        val after = provider.snapshot()

        val measured = before != null && after != null
        val freed = if (before != null && after != null) {
            (after.availableBytes - before.availableBytes).coerceAtLeast(0L)
        } else 0L

        ClearMemoryResult(
            packagesRequested = requested,
            freedBytes = freed,
            before = before,
            after = after,
            note = noteFor(requested, freed, measured)
        )
    }

    private fun noteFor(requested: Int, freedBytes: Long, measured: Boolean): String {
        val reload = "Android may reload these apps, which can use more battery."
        return when {
            !measured ->
                "Asked Android to trim background processes of $requested app(s), but memory could not be read, so the effect is unknown. $reload"
            freedBytes == 0L ->
                "No measurable gain in available memory (asked about $requested app(s)). Android was already managing it. $reload"
            else ->
                "Available memory rose by about ${freedBytes / (1024 * 1024)} MB right after asking Android to trim $requested app(s). " +
                    "Other activity can change this number too. $reload"
        }
    }
}
