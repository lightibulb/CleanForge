package com.cleanforge.core.scanner

import com.cleanforge.core.backend.FileReadBackend
import com.cleanforge.core.backend.PackageInventory
import com.cleanforge.core.model.ConfidenceLevel
import com.cleanforge.core.model.RiskLevel
import com.cleanforge.core.privileged.PrivilegedOperation
import java.util.concurrent.TimeUnit

/**
 * Twitter/X media cache under Android/data, files older than 7 days.
 *
 * DORMANT: not registered in the scanner set. These items live in Android/data, so the Shizuku service would have to delete
 * single files, and it deliberately has no such operation (MaintenanceModel.toMaintenanceRequest returns null for
 * FORENSIC_MEDIA). Registering this scanner before that exists would list items that can only ever be skipped.
 */
class TwitterXScanner(
    backend: FileReadBackend,
    inventory: PackageInventory,
    private val storage: String,
    timeProvider: () -> Long = { System.currentTimeMillis() }
) : ForensicFolderScanner(backend, inventory, timeProvider) {

    override val id = "twitter_x_forensics"
    override val displayName = "Twitter/X Forensics"
    override val requirement = Requirement.SHIZUKU
    override val privilegedOperations = setOf(PrivilegedOperation.LIST_EXTERNAL_APP_DATA)
    override val mode = ScanMode.ON_DEMAND

    override val packages = listOf("com.twitter.android", "com.twitter.android.lite")

    override fun targets(pkg: String): List<Target> = listOf(
        Target(
            dir = "$storage/Android/data/$pkg/cache",
            reason = "Cached Twitter/X media.",
            minAgeMs = TimeUnit.DAYS.toMillis(7),
            confidence = ConfidenceLevel.HIGH,
            risk = RiskLevel.SAFE
        )
    )
}
