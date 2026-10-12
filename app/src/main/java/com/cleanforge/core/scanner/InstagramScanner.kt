package com.cleanforge.core.scanner

import com.cleanforge.core.backend.FileReadBackend
import com.cleanforge.core.backend.PackageInventory
import com.cleanforge.core.fs.PathRules
import com.cleanforge.core.model.ConfidenceLevel
import com.cleanforge.core.model.RiskLevel
import com.cleanforge.core.privileged.PrivilegedOperation
import java.util.concurrent.TimeUnit

/**
 * Instagram and Instagram Lite cache under Android/data. NOT REGISTERED in AppModule: same reason as [TikTokScanner].
 * MEDIUM confidence because the cache layout varies by version.
 */
class InstagramScanner(
    backend: FileReadBackend,
    inventory: PackageInventory,
    private val root: String = PathRules.ROOT,
    timeProvider: () -> Long = { System.currentTimeMillis() }
) : ForensicFolderScanner(backend, inventory, timeProvider) {

    override val id = "instagram_forensics"
    override val displayName = "Instagram caches"
    override val requirement = Requirement.SHIZUKU
    override val mode = ScanMode.ON_DEMAND
    override val privilegedOperations = setOf(PrivilegedOperation.LIST_EXTERNAL_APP_DATA)

    override val packages = listOf("com.instagram.android", "com.instagram.lite")

    override fun targets(pkg: String): List<Target> = listOf(
        Target(
            dir = "$root/Android/data/$pkg/cache",
            reason = "Instagram media cache; the app rebuilds it.",
            minAgeMs = TimeUnit.DAYS.toMillis(14),
            confidence = ConfidenceLevel.MEDIUM,
            risk = RiskLevel.SAFE
        )
    )
}
