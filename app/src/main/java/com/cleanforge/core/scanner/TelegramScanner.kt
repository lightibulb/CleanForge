package com.cleanforge.core.scanner

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

class TelegramScanner(
    private val backend: FileOperationBackend,
    private val inventory: PackageInventory,
    private val storage: String,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) : Scanner {

    override val id = "telegram_forensics"
    override val displayName = "Telegram Forensics"
    // Android/media is accessible via MANAGE_EXTERNAL_STORAGE, not Shizuku.
    override val requirement = Requirement.ALL_FILES_ACCESS
    override val mode = ScanMode.ON_DEMAND

    private val pkg = "org.telegram.messenger"

    override fun scan(): Flow<List<ScanItem>> = flow {
        if (!inventory.isCurrentlyInstalled(pkg)) return@flow

        val base = "$storage/Android/media/$pkg/Telegram"
        val now = timeProvider()
        val minAge = TimeUnit.DAYS.toMillis(14)
        val batch = mutableListOf<ScanItem>()

        val targets = listOf(
            "$base/Telegram Video" to "Telegram Video Cache (>14 days)",
            "$base/Telegram Audio" to "Telegram Audio Cache (>14 days)",
            "$base/Telegram Images" to "Telegram Image Cache (>14 days)",
            "$base/Telegram Documents" to "Telegram Document Cache (>14 days)"
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
                                    risk = RiskLevel.ELEVATED,
                                    owningPackage = pkg,
                                    requiresShizuku = false,
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