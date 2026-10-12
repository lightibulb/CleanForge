package com.cleanforge.core.scanner

import com.cleanforge.core.backend.FileReadBackend
import com.cleanforge.core.backend.PackageInventory
import com.cleanforge.core.fs.PathRules
import com.cleanforge.core.model.ConfidenceLevel
import com.cleanforge.core.model.RiskLevel
import com.cleanforge.core.privileged.PrivilegedOperation
import java.util.concurrent.TimeUnit

/**
 * Twitter/X and X Lite cache under Android/data. NOT REGISTERED in AppModule: same reason as [TikTokScanner]
 * (no privileged single-file delete; CacheScanner already covers `cache/`).
 */
class TwitterXScanner(
    backend: FileReadBackend,
    inventory: PackageInventory,
    private val root: String = PathRules.ROOT,
    timeProvider: () -> Long = { System.currentTimeMillis() }
) : ForensicFolderScanner(backend, inventory, timeProvider) {

    override val id = "twitter_x_forensics"
    override val displayName = "Twitter/X caches"
    override val requirement = Requirement.SHIZUKU
    override val mode = ScanMode.ON_DEMAND
    override val privilegedOperations = setOf(PrivilegedOperation.LIST_EXTERNAL_APP_DATA)

    override val packages = listOf("com.twitter.android", "com.twitter.android.lite")

    override fun targets(pkg: String): List<Target> = listOf(
        Target(
            dir = "$root/Android/data/$pkg/cache",
            reason = "Twitter/X media cache; the app rebuilds it.",
            minAgeMs = TimeUnit.DAYS.toMillis(7),
            confidence = ConfidenceLevel.HIGH,
            risk = RiskLevel.SAFE
        )
    )
}
