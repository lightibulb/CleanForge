package com.cleanforge.core.scanner

import com.cleanforge.core.backend.FileOperationBackend
import com.cleanforge.core.backend.MIN_PLAUSIBLE_PACKAGES
import com.cleanforge.core.backend.PackageInventory
import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.fs.FsType
import com.cleanforge.core.fs.PathRules
import com.cleanforge.core.model.CleaningCategory
import com.cleanforge.core.model.ConfidenceLevel
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
 * Leftover folders of uninstalled apps. This can destroy game saves, so it is NEVER pre-selected and is
 * ELEVATED risk. Evidence required (all of): valid package-name folder, package unknown to the OS for the current
 * user/profile (a package uninstalled with its data kept is still "known", so it is NOT flagged) and not installed,
 * real directory (not a link), untouched for at least [minAgeDays].
 * If the package list cannot be read or looks implausibly small, NOTHING is flagged.
 */
class CorpseScanner(
    private val backend: FileOperationBackend,
    private val inventory: PackageInventory,
    private val root: String = PathRules.ROOT,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val minAgeDays: Int = 14
) : Scanner {
    override val id = "corpse"
    override val displayName = "Uninstalled-app leftovers"
    override val requirement = Requirement.SHIZUKU
    override val mode = ScanMode.QUICK

    override fun scan(): Flow<List<ScanItem>> = flow {
        val known = inventory.knownPackageNames()
        if (known == null || known.size < MIN_PLAUSIBLE_PACKAGES) return@flow

        val batch = ArrayList<ScanItem>()
        for (area in listOf("data", "obb")) {
            val areaRoot = "$root/Android/$area"
            val names = backend.list(areaRoot) ?: continue
            for (pkg in names) {
                currentCoroutineContext().ensureActive()
                if (!PathRules.isValidPackageName(pkg) || pkg in known) continue
                if (inventory.isCurrentlyInstalled(pkg)) continue // inconsistent inventory: never call an installed app a leftover
                val dir = "$areaRoot/$pkg"
                val st = backend.lstat(dir) ?: continue
                if (st.type != FsType.DIRECTORY) continue
                val ageDays = (nowMs() - st.mtimeMs) / DAY_MS
                if (ageDays < minAgeDays) continue
                val size = backend.directorySize(dir) ?: continue
                if (size.bytes <= 0L) continue
                batch += ScanItem(
                    path = dir,
                    sizeBytes = size.bytes,
                    category = CleaningCategory.CORPSE_LEFTOVER,
                    reasonForFlagging = "Android lists no app named $pkg for this user profile, neither installed nor uninstalled with data kept; " +
                        "this Android/$area folder was last changed $ageDays days ago. " +
                        "It may still hold game saves or downloads you could want if you reinstall.",
                    confidence = ConfidenceLevel.MEDIUM,
                    risk = RiskLevel.ELEVATED,
                    owningPackage = pkg,
                    requiresShizuku = true,
                    deleteMode = DeleteMode.TREE,
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
        if (batch.isNotEmpty()) emit(batch.toList())
    }.flowOn(Dispatchers.IO)
}
