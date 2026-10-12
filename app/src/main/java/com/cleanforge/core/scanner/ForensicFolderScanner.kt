package com.cleanforge.core.scanner

import com.cleanforge.core.backend.FileReadBackend
import com.cleanforge.core.backend.FsResult
import com.cleanforge.core.backend.InstallState
import com.cleanforge.core.backend.PackageInventory
import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.fs.FsStat
import com.cleanforge.core.fs.FsType
import com.cleanforge.core.model.CleaningCategory
import com.cleanforge.core.model.ConfidenceLevel
import com.cleanforge.core.model.ProtectedApps
import com.cleanforge.core.model.Reversibility
import com.cleanforge.core.model.RiskLevel
import com.cleanforge.core.model.ScanItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Shared engine of the Part 3 forensic scanners. A subclass only declares WHICH packages and WHICH exact folders;
 * everything that can go wrong (install state, unreadable folders, cancellation, batching) is handled here once.
 *
 * Evidence for an item (all of): the owning package is INSTALLED right now (tri-state [InstallState], never a bare
 * boolean), the file is a direct child of one allowlisted folder, and it is older than that folder's threshold.
 * Only regular files are ever proposed (DeleteMode.FILE); sub-folders are counted and reported, never proposed.
 *
 * Anything that could not be checked is reported through [incompleteReasons]: an empty result with reasons means
 * "could not check", not "nothing to clean". Nothing here is ever pre-selected ([ScanItem.isPreselected] is derived
 * and is false for FORENSIC_MEDIA).
 */
abstract class ForensicFolderScanner(
    private val backend: FileReadBackend,
    private val inventory: PackageInventory,
    private val timeProvider: () -> Long
) : Scanner {

    /** One allowlisted folder. Only its direct child FILES are considered. */
    protected class Target(
        val dir: String,
        val reason: String,
        val minAgeMs: Long,
        val confidence: ConfidenceLevel,
        val risk: RiskLevel,
        /** Only names that pass are considered at all. */
        val nameFilter: (String) -> Boolean = { true },
        /** The newest N files that pass [nameFilter] are never proposed, whatever their age. */
        val keepNewest: Int = 0
    )

    /** Packages to look at, in order. Each one is scanned only if it is installed. */
    protected abstract val packages: List<String>

    /** Absolute folders to inspect for [pkg]. */
    protected abstract fun targets(pkg: String): List<Target>

    @Volatile private var incomplete: List<String> = emptyList()

    override fun incompleteReasons(): List<String> = incomplete

    private val needsShizuku: Boolean get() = requirement == Requirement.SHIZUKU

    override fun scan(): Flow<List<ScanItem>> = flow {
        incomplete = emptyList()
        val now = timeProvider()
        val batch = ArrayList<ScanItem>()
        val notes = ArrayList<String>()
        var unclassified = 0
        var unreadable = 0
        var skippedNonFiles = 0
        var truncated = false
        var stoppedBecause: String? = null

        packageLoop@ for (pkg in packages) {
            currentCoroutineContext().ensureActive()
            when (inventory.installState(pkg)) {
                InstallState.INSTALLED -> Unit
                InstallState.UNKNOWN -> {
                    unclassified++
                    continue@packageLoop
                }
                else -> continue@packageLoop // not installed: nothing of this app is ours to look at
            }

            for (target in targets(pkg)) {
                currentCoroutineContext().ensureActive()
                val listing = when (val r = backend.list(target.dir)) {
                    is FsResult.Ok -> r.value
                    FsResult.NotFound -> continue // this app simply has no such folder
                    is FsResult.Unavailable -> {
                        stoppedBecause = r.why
                        break@packageLoop
                    }
                    else -> {
                        unreadable++
                        continue
                    }
                }
                if (listing.truncated) truncated = true

                val candidates = ArrayList<Pair<String, FsStat>>()
                for (name in listing.names) {
                    currentCoroutineContext().ensureActive()
                    if (!target.nameFilter(name)) continue
                    val path = "${target.dir}/$name"
                    when (val s = backend.lstat(path)) {
                        is FsResult.Ok ->
                            if (s.value.type == FsType.FILE) candidates += path to s.value else skippedNonFiles++
                        FsResult.NotFound -> Unit // vanished between list and stat
                        is FsResult.Unavailable -> {
                            stoppedBecause = s.why
                            break@packageLoop
                        }
                        else -> unreadable++
                    }
                }

                val proposable = candidates
                    .sortedByDescending { (_, st) -> st.mtimeMs }
                    .drop(target.keepNewest)

                for ((path, st) in proposable) {
                    val age = now - st.mtimeMs
                    if (age < target.minAgeMs) continue // also drops files dated in the future
                    batch += ScanItem(
                        path = path,
                        sizeBytes = st.size,
                        category = CleaningCategory.FORENSIC_MEDIA,
                        reasonForFlagging = reasonFor(target, pkg, age),
                        confidence = target.confidence,
                        risk = target.risk,
                        owningPackage = pkg,
                        requiresShizuku = needsShizuku,
                        deleteMode = DeleteMode.FILE,
                        reversibility = Reversibility.NOT_REVERSIBLE,
                        lastModified = st.mtimeMs,
                        fingerprint = st
                    )
                    if (batch.size >= BATCH_SIZE) {
                        emit(batch.toList())
                        batch.clear()
                    }
                }
            }
        }

        if (unclassified > 0) notes += "$unclassified app(s) could not be classified as installed or not and were skipped"
        if (unreadable > 0) notes += "$unreadable folder(s) or file(s) could not be read and were skipped"
        if (skippedNonFiles > 0) notes += "$skippedNonFiles sub-folder(s) were not examined (only single files are offered)"
        if (truncated) notes += "A folder has more entries than could be listed; some files were not checked"
        stoppedBecause?.let { notes += "The scan stopped early: $it" }
        incomplete = notes

        if (batch.isNotEmpty()) emit(batch.toList())
    }.flowOn(Dispatchers.IO)

    private fun reasonFor(target: Target, pkg: String, ageMs: Long): String = buildString {
        append(target.reason)
        if (target.minAgeMs > 0) append(" Last changed ${ageMs / DAY_MS} day(s) ago.")
        if (pkg in ProtectedApps.MESSAGING) append(" Messaging app: never pre-selected.")
    }
}
