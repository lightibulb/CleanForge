package com.cleanforge.core.model

data class RejectedItem(val item: ScanItem, val reason: String)

/** Output of VALIDATE + PLAN: what would be deleted, and what was refused (with reasons). */
data class CleaningPlan(
    val items: List<ScanItem>,
    val rejected: List<RejectedItem>
) {
    val totalBytes: Long get() = items.sumOf { it.sizeBytes }
    val needsShizuku: Boolean get() = items.any { it.requiresShizuku }
    val hasNonSafeItems: Boolean get() = items.any { it.risk != RiskLevel.SAFE }

    /** True if at least one size in the plan is a lower bound, so [totalBytes] is "at least". */
    val hasLowerBoundSizes: Boolean get() = items.any { it.sizeQuality == SizeQuality.LOWER_BOUND }
    val isEmpty: Boolean get() = items.isEmpty()
}

/**
 * Proof that a human looked at a plan and said yes. The engine only accepts this type,
 * so a scan result can never be turned into a deletion by accident.
 */
class ConfirmedPlan internal constructor(val plan: CleaningPlan)

/** Call ONLY from the UI after the user pressed the confirm button. */
fun CleaningPlan.confirmedByUser(): ConfirmedPlan = ConfirmedPlan(this)

/**
 * DELETED: done and checked. PARTIAL: some of it done. UNVERIFIED: may have happened, could not be confirmed.
 * SKIPPED: deliberately not attempted (changed, refused, not supported right now). FAILED: attempted and failed.
 */
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

    /** Logical bytes the deleter reported removing, including partial and unconfirmed items. */
    val bytesFreed: Long get() = outcomes.sumOf { it.bytesFreed }

    /** The part of [bytesFreed] that belongs to items whose result could not be confirmed. */
    val unconfirmedBytes: Long get() = outcomes.filter { it.status == ItemStatus.UNVERIFIED }.sumOf { it.bytesFreed }
    val fullySuccessful: Boolean get() = outcomes.all { it.status == ItemStatus.DELETED }
}
