package com.cleanforge.core.model

data class RejectedItem(val item: ScanItem, val reason: String)

data class CleaningPlan(
    val items: List<ScanItem>,
    val rejected: List<RejectedItem>,
    val permissionNotes: Map<String, PermissionNote> = emptyMap(),
    val hasBlockingPermissions: Boolean = false
) {
    val totalBytes: Long get() = items.sumOf { it.sizeBytes }
    val needsShizuku: Boolean get() = items.any { it.requiresShizuku }
    val hasNonSafeItems: Boolean get() = items.any { it.risk != RiskLevel.SAFE }
    val hasLowerBoundSizes: Boolean get() = items.any { it.sizeQuality == SizeQuality.LOWER_BOUND }
    val isEmpty: Boolean get() = items.isEmpty()
    val allItemsBlocked: Boolean
        get() = items.isNotEmpty() && items.all { permissionNotes[it.path]?.blocking == true }
}

class ConfirmedPlan internal constructor(val plan: CleaningPlan)

fun CleaningPlan.confirmedByUser(): ConfirmedPlan = ConfirmedPlan(this)

enum class ItemStatus { DELETED, PARTIAL, UNVERIFIED, SKIPPED, FAILED }

data class ItemOutcome(
    val item: ScanItem,
    val status: ItemStatus,
    val message: String,
    val bytesFreed: Long = 0L
)

data class CleaningReport(val outcomes: List<ItemOutcome>) {
    val attempted: Int get() = outcomes.size
    val deleted: Int get() = outcomes.count { it.status == ItemStatus.DELETED }
    val skipped: Int get() = outcomes.count { it.status == ItemStatus.SKIPPED }
    val failed: Int get() = outcomes.count { it.status == ItemStatus.FAILED }
    val partial: Int get() = outcomes.count { it.status == ItemStatus.PARTIAL }
    val unverified: Int get() = outcomes.count { it.status == ItemStatus.UNVERIFIED }
    val bytesFreed: Long get() = outcomes.sumOf { it.bytesFreed }
    val unconfirmedBytes: Long get() = outcomes.filter { it.status == ItemStatus.UNVERIFIED }.sumOf { it.bytesFreed }
    val fullySuccessful: Boolean get() = outcomes.all { it.status == ItemStatus.DELETED }
}
