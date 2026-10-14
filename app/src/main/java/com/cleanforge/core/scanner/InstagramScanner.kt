package com.cleanforge.core.scanner

import com.cleanforge.core.privileged.PrivilegedOperation
import com.cleanforge.core.backend.FileOperationBackend
import com.cleanforge.core.backend.PackageInventory
import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.fs.FsType
import com.cleanforge.core.model.CleaningCategory
import com.cleanforge.core.model.ConfidenceLevel
import com.cleanforge.core.model.Reversibility
import com.cleanforge.core.model.RiskLevel
import com.cleanforge.core.model.ScanItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.TimeUnit

class InstagramScanner(
    private val backend: FileOperationBackend,
    private val inventory: PackageInventory,
    private val storage: String,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) : Scanner {

    override val id = "instagram_forensics"
    override val displayName = "Instagram Forensics"
    override val requirement = Requirement.SHIZUKU
	override val privilegedOperations = setOf(
    PrivilegedOperation.LIST_EXTERNAL_APP_DATA
)
    override val mode = ScanMode.ON_DEMAND

    private val pkg = "com.instagram.android"
    private val pkgLite = "com.instagram.lite"

    override fun scan(): Flow<List<ScanItem>> = flow {
        val activePkg = when {
            inventory.isCurrentlyInstalled(pkg) -> pkg
            inventory.isCurrentlyInstalled(pkgLite) -> pkgLite
            else -> return@flow
        }

        val now = timeProvider()
        val minAge = TimeUnit.DAYS.toMillis(14)
        val batch = mutableListOf<ScanItem>()

        val targets = listOf(
            "$storage/Android/data/$activePkg/cache" to "Instagram Media Cache (>14 days)"
        )

        targets.forEach { (folderPath, reason) ->
            runCatching {
                val childNames = backend.list(folderPath) ?: return@runCatching
                childNames.forEach { name ->
                    val fullPath = "$folderPath/$name"
                    val stat = backend.lstat(fullPath) ?: return@forEach
                    
                    if (stat.type == FsType.FILE) {
                        val age = now - stat.mtimeMs
                        if (age >= minAge) {
                            batch.add(
                                ScanItem(
                                    path = fullPath,
                                    sizeBytes = stat.size,
                                    category = CleaningCategory.FORENSIC_MEDIA,
                                    reasonForFlagging = reason,
                                    confidence = ConfidenceLevel.MEDIUM,
                                    risk = RiskLevel.SAFE,
                                    owningPackage = activePkg,
                                    requiresShizuku = true,
                                    deleteMode = DeleteMode.FILE,
                                    reversibility = Reversibility.NOT_REVERSIBLE,
                                    lastModified = stat.mtimeMs,
                                    fingerprint = stat,
                                    isPreselected = false
                                )
                            )
                            if (batch.size >= BATCH_SIZE) {
                                emit(batch.toList())
                                batch.clear()
                            }
                        }
                    }
                }
            }
        }

        if (batch.isNotEmpty()) emit(batch.toList())

    }.flowOn(Dispatchers.IO)
}