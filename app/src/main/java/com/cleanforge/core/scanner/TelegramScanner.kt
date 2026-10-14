package com.cleanforge.core.scanner

import com.cleanforge.core.backend.FileReadBackend
import com.cleanforge.core.backend.PackageInventory
import com.cleanforge.core.model.ConfidenceLevel
import com.cleanforge.core.model.RiskLevel
import java.util.concurrent.TimeUnit

/** Telegram's four media folders under Android/media, files older than 14 days. Never pre-selected. */
class TelegramScanner(
    backend: FileReadBackend,
    inventory: PackageInventory,
    private val storage: String,
    timeProvider: () -> Long = { System.currentTimeMillis() }
) : ForensicFolderScanner(backend, inventory, timeProvider) {

    override val id = "telegram_forensics"
    override val displayName = "Telegram Forensics"

    // Android/media is reachable with All-files access; no Shizuku needed.
    override val requirement = Requirement.ALL_FILES_ACCESS
    override val mode = ScanMode.ON_DEMAND

    override val packages = listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.plus")

    override fun targets(pkg: String): List<Target> {
        val base = "$storage/Android/media/$pkg/Telegram"
        fun folder(name: String, what: String) = Target(
            dir = "$base/$name",
            reason = "$what that Telegram downloaded to this phone.",
            minAgeMs = TimeUnit.DAYS.toMillis(14),
            confidence = ConfidenceLevel.MEDIUM,
            risk = RiskLevel.ELEVATED
        )
        return listOf(
            folder("Telegram Video", "A video"),
            folder("Telegram Audio", "An audio file"),
            folder("Telegram Images", "An image"),
            folder("Telegram Documents", "A document")
        )
    }
}
