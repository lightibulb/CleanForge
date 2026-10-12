package com.cleanforge.core.scanner

import com.cleanforge.core.backend.FileReadBackend
import com.cleanforge.core.backend.PackageInventory
import com.cleanforge.core.fs.PathRules
import com.cleanforge.core.model.ConfidenceLevel
import com.cleanforge.core.model.RiskLevel
import java.util.concurrent.TimeUnit

/**
 * WhatsApp / WhatsApp Business. Android/media is reachable with All-files access (only Android/data and Android/obb
 * need Shizuku), so this scanner uses the standard backend and its items are deleted by the app process.
 *
 *  - `.Statuses`: status media older than 24 h.
 *  - `Sent` folders: WhatsApp's own copy of media you sent. NOT proven to exist elsewhere (an edited or forwarded
 *    file may have no other copy), so it is ELEVATED/MEDIUM and always the user's call.
 *  - `Databases/msgstore-*`: local chat backups older than 7 days; the newest one is always kept.
 */
class WhatsAppScanner(
    backend: FileReadBackend,
    inventory: PackageInventory,
    private val root: String = PathRules.ROOT,
    timeProvider: () -> Long = { System.currentTimeMillis() }
) : ForensicFolderScanner(backend, inventory, timeProvider) {

    override val id = "whatsapp_forensics"
    override val displayName = "WhatsApp media & backups"
    override val requirement = Requirement.ALL_FILES_ACCESS
    override val mode = ScanMode.ON_DEMAND

    override val packages = listOf("com.whatsapp", "com.whatsapp.w4b")

    override fun targets(pkg: String): List<Target> {
        val base = "$root/Android/media/$pkg/WhatsApp"
        val sentReason = "Copy WhatsApp keeps of media you sent. It may be the only copy of an edited or forwarded file, so check before deleting."
        fun sent(folder: String) = Target(
            dir = "$base/Media/$folder/Sent",
            reason = sentReason,
            minAgeMs = 0L,
            confidence = ConfidenceLevel.MEDIUM,
            risk = RiskLevel.ELEVATED
        )
        return listOf(
            Target(
                dir = "$base/Media/.Statuses",
                reason = "WhatsApp status media; statuses expire after 24 hours.",
                minAgeMs = TimeUnit.HOURS.toMillis(24),
                confidence = ConfidenceLevel.HIGH,
                risk = RiskLevel.SAFE
            ),
            sent("WhatsApp Video"),
            sent("WhatsApp Images"),
            sent("WhatsApp Animated Gifs"),
            Target(
                dir = "$base/Databases",
                reason = "Older local chat backup; the newest one is kept. Only copy of that day's chats if cloud backup is off.",
                minAgeMs = TimeUnit.DAYS.toMillis(7),
                confidence = ConfidenceLevel.HIGH,
                risk = RiskLevel.ELEVATED,
                nameFilter = { it.startsWith("msgstore-") },
                keepNewest = 1
            )
        )
    }
}
