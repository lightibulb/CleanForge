package com.cleanforge.core.scanner

import com.cleanforge.core.backend.FileOperationBackend
import com.cleanforge.core.backend.MIN_PLAUSIBLE_PACKAGES
import com.cleanforge.core.backend.PackageInventory
import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.fs.FsType
import com.cleanforge.core.fs.PathRules
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
 * Evidence for "safe to clear" (all of): the OS names Android/data/<pkg>/cache as that app's disposable external
 * cache (Context.getExternalCacheDir), AND <pkg> is CURRENTLY INSTALLED for this user. "Known to Android" is not
 * enough: a package uninstalled with its data kept is still known, but its folder is retained data, not a cache
 * of a running app, so it is never offered here (and CorpseScanner does not call it a leftover either).
 * Clears CONTENTS only; the folder stays.
 * Needs Shizuku because Android 11+ hides Android/data from normal apps, even with All-files access.
 */
class CacheScanner(
    private val backend: FileOperationBackend,
    private val inventory: PackageInventory,
    private val root: String = PathRules.ROOT
) : Scanner {
    override val id = "cache"
    override val displayName = "App caches"
    override val requirement = Requirement.SHIZUKU
    override val mode = ScanMode.QUICK

    override fun scan(): Flow<List<ScanItem>> = flow {
        val known = inventory.knownPackageNames()
        if (known == null || known.size < MIN_PLAUSIBLE_PACKAGES) return@flow // cannot verify -> flag nothing

        val dataRoot = "$root/Android/data"
        val names = backend.list(dataRoot) ?: return@flow
        val batch = ArrayList<ScanItem>()
        for (pkg in names) {
            currentCoroutineContext().ensureActive()
            if (!PathRules.isValidPackageName(pkg) || pkg !in known) continue
            // `known` includes data-kept uninstalls (MATCH_UNINSTALLED_PACKAGES). Installed-ness is a separate question.
            if (!inventory.isCurrentlyInstalled(pkg)) continue
            val cacheDir = "$dataRoot/$pkg/cache"
            val st = backend.lstat(cacheDir) ?: continue
            if (st.type != FsType.DIRECTORY) continue
            val size = backend.directorySize(cacheDir) ?: continue
            if (size.bytes <= 0L) continue
            val messaging = pkg in ProtectedApps.MESSAGING
            batch += ScanItem(
                path = cacheDir,
                sizeBytes = size.bytes,
                category = CleaningCategory.APP_CACHE,
                reasonForFlagging = "Android designates this as the disposable cache of $pkg, which is installed; the app rebuilds it " +
                    "and the system may clear it when space runs low." +
                    if (messaging) " Messaging app: not pre-selected." else "",
                confidence = if (messaging) ConfidenceLevel.MEDIUM else ConfidenceLevel.HIGH,
                risk = RiskLevel.SAFE,
                owningPackage = pkg,
                requiresShizuku = true,
                deleteMode = DeleteMode.CONTENTS_ONLY,
                reversibility = Reversibility.TRIVIALLY_RECREATABLE,
                lastModified = st.mtimeMs,
                fingerprint = st
            )
            if (batch.size >= BATCH_SIZE) {
                emit(batch.toList())
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) emit(batch.toList())
    }.flowOn(Dispatchers.IO)
}
