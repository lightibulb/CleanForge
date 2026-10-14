package com.cleanforge.domain.usecase

import com.cleanforge.core.backend.PackageInventory
import com.cleanforge.core.memory.BackgroundProcessKiller
import com.cleanforge.core.memory.ClearMemoryResult
import com.cleanforge.core.memory.MemoryInfoProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The real, limited, honest RAM-clear.
 *
 * It delegates the actual trim request to [BackgroundProcessKiller], which skips
 * CleanForge itself, system apps, and unknown app types.
 *
 * It then MEASURES the actual change in available memory instead of inventing a number.
 */
class ClearMemoryUseCase @Inject constructor(
    private val killer: BackgroundProcessKiller,
    private val inventory: PackageInventory,
    private val provider: MemoryInfoProvider
) {

    suspend operator fun invoke(): ClearMemoryResult = withContext(Dispatchers.IO) {
        val before = provider.snapshot()
        var requested = 0

        val installed: Iterable<String> = inventory.knownPackageNames()
            ?.filter { inventory.isCurrentlyInstalled(it) }
            ?: emptySet()

        var count = 0
        for (pkg in installed) {
            // Periodic cancellation check for large package lists.
            if (count++ % 64 == 0) {
                currentCoroutineContext().ensureActive()
            }

            if (killer.kill(pkg)) {
                requested++
            }
        }

        // Give the kernel a beat to actually reclaim the pages.
        delay(600L)

        val after = provider.snapshot()

        val freed = if (before != null && after != null) {
            (after.availableBytes - before.availableBytes).coerceAtLeast(0L)
        } else {
            0L
        }

        val note = if (before == null || after == null) {
            "Memory info was unavailable, so no measured change could be calculated."
        } else {
            "Freed ${freed / (1024 * 1024)} MB for now. Android may reload these apps, which can use more battery."
        }

        ClearMemoryResult(
            processesTouched = requested,
            freedBytes = freed,
            before = before,
            after = after,
            note = note
        )
    }
}