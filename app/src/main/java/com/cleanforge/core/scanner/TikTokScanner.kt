package com.cleanforge.core.scanner

import com.cleanforge.core.backend.FileReadBackend
import com.cleanforge.core.backend.PackageInventory
import com.cleanforge.core.fs.PathRules
import com.cleanforge.core.model.ConfidenceLevel
import com.cleanforge.core.model.RiskLevel
import com.cleanforge.core.privileged.PrivilegedOperation
import java.util.concurrent.TimeUnit

/**
 * TikTok / TikTok Lite under Android/data (reads need Shizuku, so pass the Shizuku read backend).
 *
 * NOT REGISTERED in AppModule on purpose: the Shizuku service can only clear a cache folder's CONTENTS or remove an
 * uninstalled app's folder (see PathRules.isServiceDeletable); it has no single-file delete. Items from this scanner
 * would always be skipped at deletion. CacheScanner already offers `cache/` as APP_CACHE. Register this only after a
 * privileged FILE-delete operation exists.
 */
class TikTokScanner(
    backend: FileReadBackend,
    inventory: PackageInventory,
    private val root: String = PathRules.ROOT,
    timeProvider: () -> Long = { System.currentTimeMillis() }
) : ForensicFolderScanner(backend, inventory, timeProvider) {

    override val id = "tiktok_forensics"
    override val displayName = "TikTok caches"
    override val requirement = Requirement.SHIZUKU
    override val mode = ScanMode.ON_DEMAND
    override val privilegedOperations = setOf(PrivilegedOperation.LIST_EXTERNAL_APP_DATA)

    override val packages = listOf("com.zhiliaoapp.musically", "com.zhiliaoapp.musically.go")

    override fun targets(pkg: String): List<Target> = listOf(
        Target(
            dir = "$root/Android/data/$pkg/cache",
            reason = "TikTok video cache; the app rebuilds it.",
            minAgeMs = TimeUnit.DAYS.toMillis(7),
            confidence = ConfidenceLevel.HIGH,
            risk = RiskLevel.SAFE
        ),
        Target(
            dir = "$root/Android/data/$pkg/files/.music",
            reason = "TikTok music cache; the app downloads it again.",
            minAgeMs = TimeUnit.DAYS.toMillis(7),
            confidence = ConfidenceLevel.HIGH,
            risk = RiskLevel.SAFE
        )
    )
}
