package com.cleanforge.domain.usecase

import android.content.Context
import android.content.pm.PackageManager
import com.cleanforge.core.backend.FsResult
import com.cleanforge.core.privileged.PrivilegedExecutor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** RAM held by one app: the sum over all of its processes (resident set size, includes pages shared with the system). */
data class AppMemory(val label: String, val packageName: String, val bytes: Long, val processes: Int)

/** [unavailable] is the reason there is no list (Shizuku missing, service too old...); never "nothing is running". */
data class TopProcesses(val apps: List<AppMemory>, val unavailable: String? = null)

/**
 * Which apps hold the most memory right now. Android hides other apps' processes from normal apps, so this needs the
 * Shizuku service (read-only; it reports app processes only).
 */
class TopProcessesUseCase @Inject constructor(
    private val executor: PrivilegedExecutor,
    @ApplicationContext private val context: Context
) {
    suspend operator fun invoke(limit: Int = 12): TopProcesses = withContext(Dispatchers.IO) {
        when (val r = executor.processMemory()) {
            is FsResult.Ok -> TopProcesses(aggregate(r.value, limit))
            is FsResult.Unavailable -> TopProcesses(emptyList(), r.why)
            is FsResult.Denied -> TopProcesses(emptyList(), r.why)
            is FsResult.Failed -> TopProcesses(emptyList(), r.why)
            FsResult.NotFound -> TopProcesses(emptyList(), "The service returned no process list")
        }
    }

    @Suppress("DEPRECATION")
    private fun aggregate(entries: List<com.cleanforge.core.privileged.ProcessMemoryEntry>, limit: Int): List<AppMemory> {
        val pm = context.packageManager
        return entries
            .groupBy { it.processName.substringBefore(':') }
            .mapNotNull { (pkg, procs) ->
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (e: PackageManager.NameNotFoundException) {
                    return@mapNotNull null // not an installed app (isolated or helper process names)
                }
                AppMemory(label, pkg, procs.sumOf { it.rssKb } * 1024L, procs.size)
            }
            .sortedByDescending { it.bytes }
            .take(limit)
    }
}
