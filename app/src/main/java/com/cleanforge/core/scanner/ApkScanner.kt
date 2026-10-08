package com.cleanforge.core.scanner

import com.cleanforge.core.backend.PackageInventory
import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.fs.FsOps
import com.cleanforge.core.fs.FsType
import com.cleanforge.core.fs.PathRules
import com.cleanforge.core.fs.StorageWalker
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
 * Installer files. The platform parser (not a regex over binary XML, as the old code did) reads the real
 * package name and version. Evidence for MEDIUM: that package is installed at the same or a NEWER version.
 * Otherwise LOW (you may still want the installer). Unreadable APKs are skipped (could be mid-download).
 * Never pre-selected.
 */
class ApkScanner(
    private val fs: FsOps,
    private val inventory: PackageInventory,
    private val root: String = PathRules.ROOT
) : Scanner {
    override val id = "apk"
    override val displayName = "APK installers"
    override val requirement = Requirement.ALL_FILES_ACCESS
    override val mode = ScanMode.QUICK

    override fun scan(): Flow<List<ScanItem>> = flow {
        val batch = ArrayList<ScanItem>()
        var n = 0
        for (dir in listOf("Download", "Documents")) {
            for (e in StorageWalker(fs).walk("$root/$dir")) {
                if (++n % 128 == 0) currentCoroutineContext().ensureActive()
                if (e.stat.type != FsType.FILE) continue
                if (!e.name.endsWith(".apk", ignoreCase = true)) continue
                val info = inventory.archiveInfo(e.path) ?: continue
                val installed = inventory.installedVersionCode(info.packageName)
                val sameOrNewerInstalled = installed != null && installed >= info.versionCode
                batch += ScanItem(
                    path = e.path,
                    sizeBytes = e.stat.size,
                    category = CleaningCategory.APK_FILE,
                    reasonForFlagging = if (sameOrNewerInstalled)
                        "Installer for ${info.packageName} v${info.versionCode}; the installed version " +
                            "(v$installed) is the same or newer."
                    else
                        "Installer for ${info.packageName} v${info.versionCode}. " +
                            (if (installed == null) "That app is not installed" else "Installed version is older (v$installed)") +
                            ", so you may still want this file.",
                    confidence = if (sameOrNewerInstalled) ConfidenceLevel.MEDIUM else ConfidenceLevel.LOW,
                    risk = RiskLevel.ELEVATED,
                    owningPackage = info.packageName,
                    deleteMode = DeleteMode.FILE,
                    reversibility = Reversibility.RE_DOWNLOADABLE,
                    lastModified = e.stat.mtimeMs,
                    fingerprint = e.stat
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
