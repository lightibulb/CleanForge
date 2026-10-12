package com.cleanforge.core.scanner

import com.cleanforge.core.backend.FileReadBackend
import com.cleanforge.core.backend.PackageInventory
import com.cleanforge.core.fs.PathRules
import com.cleanforge.core.model.ConfidenceLevel
import com.cleanforge.core.model.RiskLevel
import java.util.concurrent.TimeUnit

/**
 * Telegram downloads under Android/media (All-files access, standard backend). ELEVATED/MEDIUM: a user may have
 * saved these on purpose, so they are never pre-selected and older than 14 days only.
 */
class TelegramScanner(
    backend: FileReadBackend,
    inventory: PackageInventory,
    private val root: String = PathRules.ROOT,
    timeProvider: () -> Long = { System.currentTimeMillis() }
) : ForensicFolderScanner(backend, inventory, timeProvider) {

    override val id = "telegram_forensics"
    override val displayName = "Telegram downloaded media"
    override val requirement = Requirement.ALL_FILES_ACCESS
    override val mode = ScanMode.ON_DEMAND

    override val packages = listOf("org.telegram.messenger")

    override fun targets(pkg: String): List<Target> {
        val base = "$root/Android/media/$pkg/Telegram"
        fun folder(name: String, what: String) = Target(
            dir = "$base/$name",
            reason = "Telegram $what you downloaded. Telegram can fetch it again from the chat, but you may have saved it on purpose.",
            minAgeMs = TimeUnit.DAYS.toMillis(14),
            confidence = ConfidenceLevel.MEDIUM,
            risk = RiskLevel.ELEVATED
        )
        return listOf(
            folder("Telegram Video", "video"),
            folder("Telegram Audio", "audio"),
            folder("Telegram Images", "image"),
            folder("Telegram Documents", "document")
        )
    }
}
