package com.cleanforge.core.scanner

import com.cleanforge.core.backend.FileReadBackend
import com.cleanforge.core.backend.PackageInventory
import com.cleanforge.core.model.ConfidenceLevel
import com.cleanforge.core.model.RiskLevel
import java.util.concurrent.TimeUnit

/**
 * WhatsApp and WhatsApp Business, whichever is installed (both, if both are). Only the exact folders below are looked
 * at, and only single files in them; everything else in WhatsApp's folders is the user's chat data and is left alone.
 * The same allowlist is enforced a second time by SafetyValidator, so a scanner bug cannot widen it.
 */
class WhatsAppScanner(
    backend: FileReadBackend,
    inventory: PackageInventory,
    private val storage: String,
    timeProvider: () -> Long = { System.currentTimeMillis() }
) : ForensicFolderScanner(backend, inventory, timeProvider) {

    override val id = "whatsapp_forensics"
    override val displayName = "WhatsApp Forensics"

    // Android/media is reachable with All-files access; no Shizuku needed.
    override val requirement = Requirement.ALL_FILES_ACCESS
    override val mode = ScanMode.ON_DEMAND

    override val packages = listOf("com.whatsapp", "com.whatsapp.w4b")

    override fun targets(pkg: String): List<Target> {
        val whatsapp = "$storage/Android/media/$pkg/WhatsApp"
        val media = "$whatsapp/Media"

        // Sent media is a copy WhatsApp kept; it is the only copy if the user never saved it to the Gallery.
        fun sent(folder: String, what: String) = Target(
            dir = "$media/$folder/Sent",
            reason = "$what that WhatsApp kept when you sent it. It may be the only copy if it is not in your Gallery.",
            minAgeMs = 0L,
            confidence = ConfidenceLevel.MEDIUM,
            risk = RiskLevel.ELEVATED
        )

        return listOf(
            Target(
                dir = "$media/.Statuses",
                reason = "A WhatsApp Status that expired more than 24 hours ago.",
                minAgeMs = TimeUnit.HOURS.toMillis(24),
                confidence = ConfidenceLevel.HIGH,
                risk = RiskLevel.SAFE
            ),
            sent("WhatsApp Video", "A video"),
            sent("WhatsApp Images", "An image"),
            sent("WhatsApp Animated Gifs", "An animated GIF"),
            Target(
                dir = "$whatsapp/Databases",
                reason = "An old WhatsApp chat backup. The newest backup is always kept.",
                minAgeMs = TimeUnit.DAYS.toMillis(7),
                confidence = ConfidenceLevel.MEDIUM,
                risk = RiskLevel.ELEVATED,
                // Dated backups only ("msgstore-2026-08-01..."): never the live "msgstore.db.crypt14" or wa.db.
                nameFilter = { it.startsWith("msgstore-") },
                keepNewest = 1
            )
        )
    }
}
