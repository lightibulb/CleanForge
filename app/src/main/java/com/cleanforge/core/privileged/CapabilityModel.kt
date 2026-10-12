package com.cleanforge.core.privileged

import com.cleanforge.core.shizuku.ShizukuState

/**
 * How far we can vouch for an operation RIGHT NOW. A live Shizuku binder is only one prerequisite; it never proves
 * that a particular operation works on this device.
 */
enum class CapabilityStatus {
    /** Demonstrated in this session by a probe or by a real operation result. */
    VERIFIED,

    /**
     * Every prerequisite that can be checked without side effects is met (Shizuku running and authorized, service
     * bound, Android/data readable), but the operation itself has not been demonstrated yet. For deletions this is
     * the best a read-only probe can say: we never delete anything just to test.
     */
    PREREQUISITES_MET,

    /** The user must act (allow CleanForge inside Shizuku). */
    NEEDS_AUTHORIZATION,

    /** Not usable right now, but may be later (Shizuku missing or stopped, service not bindable, probe failed). */
    UNAVAILABLE,

    /** This device or Shizuku version cannot do it, as probed (too-old Shizuku, OS refuses the shell). */
    UNSUPPORTED,

    /** Not checked yet. */
    UNKNOWN
}

data class OperationCapability(
    val operation: PrivilegedOperation,
    val status: CapabilityStatus,
    /** Plain-language, actionable explanation. Always set. */
    val reason: String,
    /** Honest limits of the operation even when it works. */
    val limitations: List<String> = emptyList(),
    /** Epoch millis of the probe or operation that produced [status]; null when derived without a probe. */
    val checkedAtMs: Long? = null
) {
    /** True if the operation may be attempted. It does not promise success. */
    val canAttempt: Boolean
        get() = status == CapabilityStatus.VERIFIED || status == CapabilityStatus.PREREQUISITES_MET
}

data class CapabilityReport(
    val shizuku: ShizukuState,
    /** uid the user service reported (2000 = shell, 0 = root). Null if no probe bound the service. */
    val serviceUid: Int?,
    val serviceApiVersion: Int?,
    val operations: Map<PrivilegedOperation, OperationCapability>,
    /** When the last probe finished. Null: never probed. */
    val probedAtMs: Long?
) {
    fun of(op: PrivilegedOperation): OperationCapability = operations[op] ?: OperationCapability(
        op, CapabilityStatus.UNKNOWN, "Not checked yet"
    )

    companion object {
        fun unknown(shizuku: ShizukuState): CapabilityReport = CapabilityReport(
            shizuku = shizuku,
            serviceUid = null,
            serviceApiVersion = null,
            operations = PrivilegedOperation.entries.associateWith {
                OperationCapability(it, CapabilityStatus.UNKNOWN, "Not checked yet", Limitations.of(it))
            },
            probedAtMs = null
        )
    }
}

/** Static, honest limits per operation. Shown in the UI and in docs/SHIZUKU_CAPABILITY_MATRIX.md. */
object Limitations {
    private val READ = listOf(
        "Sizes are logical bytes (sum of file sizes), not disk blocks.",
        "The walk stops at 200,000 entries or 32 levels; unreadable sub-folders are skipped. Either makes the size a lower bound."
    )

    fun of(op: PrivilegedOperation): List<String> = when (op) {
        PrivilegedOperation.LIST_EXTERNAL_APP_DATA -> listOf(
            "Only folder names in Android/data and Android/obb of the primary user; other users and work profiles are not covered.",
            "Very long listings are cut off and reported as incomplete."
        )
        PrivilegedOperation.MEASURE_EXTERNAL_APP_DIR -> READ
        PrivilegedOperation.CLEAR_EXTERNAL_APP_CACHE -> listOf(
            "External cache only (Android/data/<package>/cache). An app's internal cache under /data/data is out of reach.",
            "A running app may recreate files right away.",
            "Not proven until a deletion has succeeded in this session."
        )
        PrivilegedOperation.REMOVE_UNINSTALLED_APP_DATA -> listOf(
            "Only Android/data/<package> and Android/obb/<package>; never an app's internal data.",
            "Cannot be undone. May contain saves the user still wants.",
            "Not proven until a deletion has succeeded in this session."
        )
    }
}
