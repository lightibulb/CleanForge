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
import com.cleanforge.core.model.ProtectedApps
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
 * Evidence for "safe to clear" (all of): the OS names Android/data/<pkg>/cache as that app's disposable external
 * cache (Context.getExternalCacheDir), AND <pkg> is CURRENTLY INSTALLED for this user. "Known to Android" is not
 * enough: a package uninstalled with its data kept is still known, but its folder is retained data, not a cache
 * of a running app, so it is never offered here (and CorpseScanner does not call it a leftover either).
 * Clears CONTENTS only; the folder stays.
 * Needs Shizuku because Android 11+ hides Android/data from normal apps, even with All-files access.
 *
 * Sizes are MEASURED logical bytes. If the walk was cut short or a sub-folder was unreadable the item says so
 * ([SizeQuality.LOWER_BOUND]). Anything that could not be checked is reported through [incompleteReasons]; an empty
 * result with reasons means "could not check", not "nothing to clean".
 */
class CacheScanner(
    private val backend: FileReadBackend,
    private val inventory: PackageInventory,
    private val root: String = PathRules.ROOT
) : Scanner {
    override val id = "cache"
    override val displayName = "App caches"
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
            incomplete = listOf("Android's list of installed apps could not be read or looks incomplete, so no app cache could be verified")
            return@flow
        }

        val dataRoot = "$root/Android/data"
        val listing = when (val r = backend.list(dataRoot)) {
            is FsResult.Ok -> r.value
            else -> {
                incomplete = listOf("Android/data could not be listed (${r.reasonOrNull() ?: "folder not found"})")
                return@flow
            }
        }

        var unclassified = 0
        var unmeasured = 0
        var stoppedBecause: String? = null
        val batch = ArrayList<ScanItem>()
        for (pkg in listing.names) {
            currentCoroutineContext().ensureActive()
            if (!PathRules.isValidPackageName(pkg) || pkg !in known) continue
            // `known` includes data-kept uninstalls (MATCH_UNINSTALLED_PACKAGES). Installed-ness is a separate question,
            // and a failed query is UNKNOWN, which must never be read as "installed".
            val state = inventory.installState(pkg)
            if (state == InstallState.UNKNOWN) {
                unclassified++
                continue
            }
            if (state != InstallState.INSTALLED) continue

            val cacheDir = "$dataRoot/$pkg/cache"
            val stat = backend.lstat(cacheDir)
            if (stat is FsResult.Unavailable) {
                stoppedBecause = stat.why
                break
            }
            if (stat !is FsResult.Ok) {
                if (stat !is FsResult.NotFound) unmeasured++ // NotFound: this app simply has no cache folder
                continue
            }
            val st = stat.value
            if (st.type != FsType.DIRECTORY) continue

            val sizeRes = backend.directorySize(cacheDir)
            if (sizeRes is FsResult.Unavailable) {
                stoppedBecause = sizeRes.why
                break
            }
            if (sizeRes !is FsResult.Ok) {
                unmeasured++
                continue
            }
            val size = sizeRes.value
            if (size.bytes <= 0L) {
                if (!size.isExact) unmeasured++ // could not see inside: unknown, not "empty"
                continue
            }
            val messaging = pkg in ProtectedApps.MESSAGING
            batch += ScanItem(
                path = cacheDir,
                sizeBytes = size.bytes,
                category = CleaningCategory.APP_CACHE,
                reasonForFlagging = "Android designates this as the disposable cache of $pkg, which is installed; the app rebuilds it " +
                    "and the system may clear it when space runs low." +
                    (if (messaging) " Messaging app: not pre-selected." else "") +
                    (if (size.isExact) "" else " The size is a lower bound: part of the folder could not be read or is very large."),
                confidence = if (messaging) ConfidenceLevel.MEDIUM else ConfidenceLevel.HIGH,
                risk = RiskLevel.SAFE,
                owningPackage = pkg,
                requiresShizuku = true,
                deleteMode = DeleteMode.CONTENTS_ONLY,
                reversibility = Reversibility.TRIVIALLY_RECREATABLE,
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

        val notes = ArrayList<String>()
        if (listing.truncated) notes += "Android/data has more folders than could be listed; some apps were not checked"
        if (unclassified > 0) notes += "$unclassified app(s) could not be classified as installed or not and were skipped"
        if (unmeasured > 0) notes += "$unmeasured cache folder(s) could not be examined or measured and were skipped"
        stoppedBecause?.let { notes += "The scan stopped early: $it" }
        incomplete = notes

        if (batch.isNotEmpty()) emit(batch.toList())
    }.flowOn(Dispatchers.IO)
}
