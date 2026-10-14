package com.cleanforge.core.scanner

import com.cleanforge.core.backend.FileOperationBackend
import com.cleanforge.core.backend.PackageInventory
import com.cleanforge.core.fs.DeleteMode
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

class WhatsAppScanner(
    private val backend: FileOperationBackend,
    private val inventory: PackageInventory,
    private val storage: String,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) : Scanner {

    override val id = "whatsapp_forensics"
    override val displayName = "WhatsApp Forensics"
    // Android/media is accessible via MANAGE_EXTERNAL_STORAGE, not Shizuku.
    override val requirement = Requirement.ALL_FILES_ACCESS
    override val mode = ScanMode.ON_DEMAND

    private val pkg = "com.whatsapp"
    private val pkgBusiness = "com.whatsapp.w4b"

    override fun scan(): Flow<List<ScanItem>> = flow {
        val isInstalled = inventory.isCurrentlyInstalled(pkg) || inventory.isCurrentlyInstalled(pkgBusiness)
        if (!isInstalled) return@flow

        val activePkg = if (inventory.isCurrentlyInstalled(pkg)) pkg else pkgBusiness
        val mediaBase = "$storage/Android/media/$activePkg/WhatsApp/Media"
        val dbBase = "$storage/Android/media/$activePkg/WhatsApp/Databases"
        val now = timeProvider()
        val batch = mutableListOf<ScanItem>()

        // --- 1. .Statuses (expired > 24h) ---
        scanFolder(
            folderPath = "$mediaBase/.Statuses",
            now = now,
            minAgeMs = TimeUnit.HOURS.toMillis(24),
            reasonForFlagging = "WhatsApp Status (Expired >24h)",
            confidence = ConfidenceLevel.HIGH,
            risk = RiskLevel.SAFE,
            pkg = activePkg,
            batch = batch
        ) { emit(it) }

        // --- 2. Sent folders (duplicates of gallery) ---
        val sentTargets = listOf(
            "$mediaBase/WhatsApp Video/Sent" to "WhatsApp Sent Video (Already in Gallery)",
            "$mediaBase/WhatsApp Images/Sent" to "WhatsApp Sent Image (Already in Gallery)",
            "$mediaBase/WhatsApp Animated Gifs/Sent" to "WhatsApp Sent GIF (Already in Gallery)"
        )
        sentTargets.forEach { (path, reason) ->
            scanFolder(
                folderPath = path,
                now = now,
                minAgeMs = 0L,
                reasonForFlagging = reason,
                confidence = ConfidenceLevel.HIGH,
                risk = RiskLevel.SAFE,
                pkg = activePkg,
                batch = batch
            ) { emit(it) }
        }

        // --- 3. Old database backups (keep newest, delete rest > 7 days) ---
        runCatching {
            val childNames = backend.list(dbBase) ?: return@runCatching
            val dbFiles = childNames
                .filter { it.startsWith("msgstore-") }
                .mapNotNull { name ->
                    val fullPath = "$dbBase/$name"
                    backend.lstat(fullPath)?.let { stat -> fullPath to stat }
                }
                .sortedByDescending { (_, stat) -> stat.mtimeMs }

            dbFiles.drop(1).forEach { (fullPath, stat) ->
                val ageDays = TimeUnit.MILLISECONDS.toDays(now - stat.mtimeMs)
                if (ageDays > 7) {
                    batch.add(
                        ScanItem(
                            path = fullPath,
                            sizeBytes = stat.size,
                            category = CleaningCategory.FORENSIC_MEDIA,
                            reasonForFlagging = "Old WhatsApp Backup ($ageDays days old)",
                            confidence = ConfidenceLevel.HIGH,
                            risk = RiskLevel.SAFE,
                            owningPackage = activePkg,
                            requiresShizuku = false,
                            deleteMode = DeleteMode.FILE,
                            reversibility = Reversibility.NOT_REVERSIBLE,
                            lastModified = stat.mtimeMs,
                            fingerprint = stat,
                            isPreselected = false
                        )
                    )
                }
            }
        }

        if (batch.isNotEmpty()) { emit(batch.toList()); batch.clear() }
    }.flowOn(Dispatchers.IO)

    private suspend fun scanFolder(
        folderPath: String,
        now: Long,
        minAgeMs: Long,
        reasonForFlagging: String,
        confidence: ConfidenceLevel,
        risk: RiskLevel,
        pkg: String,
        batch: MutableList<ScanItem>,
        emitBlock: suspend (List<ScanItem>) -> Unit
    ) {
        runCatching {
            val childNames = backend.list(folderPath) ?: return@runCatching
            childNames.forEach { name ->
                val fullPath = "$folderPath/$name"
                val stat = backend.lstat(fullPath) ?: return@forEach
                if (stat.type == com.cleanforge.core.fs.FsType.FILE) {
                    val age = now - stat.mtimeMs
                    if (age >= minAgeMs) {
                        batch.add(
                            ScanItem(
                                path = fullPath,
                                sizeBytes = stat.size,
                                category = CleaningCategory.FORENSIC_MEDIA,
                                reasonForFlagging = reasonForFlagging,
                                confidence = confidence,
                                risk = risk,
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
                            emitBlock(batch.toList())
                            batch.clear()
                        }
                    }
                }
            }
        }
        // Silently skip if folder doesn't exist
    }
}