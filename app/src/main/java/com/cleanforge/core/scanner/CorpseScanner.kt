package com.cleanforge.core.scanner

import com.cleanforge.core.backend.FileReadBackend
import com.cleanforge.core.backend.FsResult
import com.cleanforge.core.backend.InstallState
import com.cleanforge.core.backend.MIN_PLAUSIBLE_PACKAGES
import com.cleanforge.core.backend.PackageInventory
import com.cleanforge.core.backend.reasonOrNull
import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.fs.FsType
import com.cleanforge.core.fs.PathRules
import com.cleanforge.core.model.CleaningCategory
import com.cleanforge.core.model.ConfidenceLevel
import com.cleanforge.core.model.Reversibility
import com.cleanforge.core.model.RiskLevel
import com.cleanforge.core.model.ScanItem
import com.cleanforge.core.model.SizeQuality
import com.cleanforge.core.privileged.PrivilegedOperation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Leftover folders of uninstalled apps. This can destroy game saves, so it is NEVER pre-selected and is
 * ELEVATED risk. Evidence required (all of): valid package-name folder, package unknown to the OS for the current
 * user/profile (a package uninstalled with its data kept is still "known", so it is NOT flagged) and not installed,
 * real directory (not a link), untouched for at least [minAgeDays].
 * The folder's existence is never evidence that the app is or is not installed; only the package manager decides,
 * and a failed package-manager query ([InstallState.UNKNOWN]) skips the folder instead of calling it a leftover.
 * If the package list cannot be read or looks implausibly small, NOTHING is flagged and the reason is reported.
 */
class CorpseScanner(
    private val backend: FileReadBackend,
    private val inventory: PackageInventory,
    private val root: String = PathRules.ROOT,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val minAgeDays: Int = 14
) : Scanner {
    override val id = "corpse"
    override val displayName = "Uninstalled-app leftovers"
    override val requirement = Requirement.SHIZUKU
    override val mode = ScanMode.QUICK
    override val privilegedOperations = setOf(
        PrivilegedOperation.LIST_EXTERNAL_APP_DATA,
        PrivilegedOperation.MEASURE_EXTERNAL_APP_DIR
    )

    @Volatile private var incomplete: List<String> = emptyList()

    override fun incompleteReasons(): List<String> = incomplete

    override fun scan(): Flow<List<ScanItem>> = flow {
        incomplete = emptyList()
        val known = inventory.knownPackageNames()
        if (known == null || known.size < MIN_PLAUSIBLE_PACKAGES) {
            incomplete = listOf("Android's list of installed apps could not be read or looks incomplete, so no leftover could be verified")
            return@flow
        }
        // Shared storage is case-insensitive: a folder spelled differently from a known package is still that package.
        val knownLower = known.mapTo(HashSet<String>()) { it.lowercase() }

        val notes = ArrayList<String>()
        var unclassified = 0
        var unmeasured = 0
        var stoppedBecause: String? = null
        val batch = ArrayList<ScanItem>()
        areas@ for (area in listOf("data", "obb")) {
            val areaRoot = "$root/Android/$area"
            val listing = when (val r = backend.list(areaRoot)) {
                is FsResult.Ok -> r.value
                else -> {
                    notes += "Android/$area could not be listed (${r.reasonOrNull() ?: "folder not found"})"
                    continue@areas
                }
            }
            if (listing.truncated) notes += "Android/$area has more folders than could be listed; some were not checked"
            for (pkg in listing.names) {
                currentCoroutineContext().ensureActive()
                if (!PathRules.isValidPackageName(pkg) || pkg in known || pkg.lowercase() in knownLower) continue
                // Only an authoritative NOT_KNOWN may become a leftover. INSTALLED / data-kept are never leftovers and
                // UNKNOWN (query failed) must not be read as "not installed".
                val state = inventory.installState(pkg)
                if (state == InstallState.UNKNOWN) {
                    unclassified++
                    continue
                }
                if (state != InstallState.NOT_KNOWN) continue

                val dir = "$areaRoot/$pkg"
                val stat = backend.lstat(dir)
                if (stat is FsResult.Unavailable) {
                    stoppedBecause = stat.why
                    break@areas
                }
                if (stat !is FsResult.Ok) {
                    if (stat !is FsResult.NotFound) unmeasured++
                    continue
                }
                val st = stat.value
                if (st.type != FsType.DIRECTORY) continue
                val ageDays = (nowMs() - st.mtimeMs) / DAY_MS
                if (ageDays < minAgeDays) continue

                val sizeRes = backend.directorySize(dir)
                if (sizeRes is FsResult.Unavailable) {
                    stoppedBecause = sizeRes.why
                    break@areas
                }
                if (sizeRes !is FsResult.Ok) {
                    unmeasured++
                    continue
                }
                val size = sizeRes.value
                if (size.bytes <= 0L) {
                    if (!size.isExact) unmeasured++
                    continue
                }
                batch += ScanItem(
                    path = dir,
                    sizeBytes = size.bytes,
                    category = CleaningCategory.CORPSE_LEFTOVER,
                    reasonForFlagging = "Android lists no app named $pkg for this user profile, neither installed nor uninstalled with data kept; " +
                        "this Android/$area folder was last changed $ageDays days ago. " +
                        "It may still hold game saves or downloads you could want if you reinstall." +
                        (if (size.isExact) "" else " The size is a lower bound: part of the folder could not be read or is very large."),
                    confidence = ConfidenceLevel.MEDIUM,
                    risk = RiskLevel.ELEVATED,
                    owningPackage = pkg,
                    requiresShizuku = true,
                    deleteMode = DeleteMode.TREE,
                    reversibility = Reversibility.NOT_REVERSIBLE,
                    lastModified = st.mtimeMs,
                    fingerprint = st,
                    sizeQuality = if (size.isExact) SizeQuality.MEASURED else SizeQuality.LOWER_BOUND,
                    unreadableCount = size.unreadable
                )
                if (batch.size >= BATCH_SIZE) {
                    emit(batch.toList())
                    batch.clear()
                }
            }
        }

        if (unclassified > 0) notes += "$unclassified folder(s) could not be matched against installed apps and were skipped"
        if (unmeasured > 0) notes += "$unmeasured folder(s) could not be examined or measured and were skipped"
        stoppedBecause?.let { notes += "The scan stopped early: $it" }
        incomplete = notes

        if (batch.isNotEmpty()) emit(batch.toList())
    }.flowOn(Dispatchers.IO)
}
