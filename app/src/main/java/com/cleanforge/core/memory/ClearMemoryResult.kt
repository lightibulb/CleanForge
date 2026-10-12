package com.cleanforge.core.memory

/**
 * Result of the honest clear action. [freedBytes] is a MEASURED delta in MemAvailable (never an estimate) and may
 * legitimately be 0. [packagesRequested] counts apps Android was ASKED to trim; it is not a count of processes that
 * actually died (killBackgroundProcesses does not report that).
 */
data class ClearMemoryResult(
    val packagesRequested: Int,
    val freedBytes: Long,
    val before: RamSnapshot?,
    val after: RamSnapshot?,
    /** Truthful copy shown to the user. Never claims a "boost". */
    val note: String
) {
    /** False when either reading failed: then [freedBytes] is 0 because it is unknown, not because nothing changed. */
    val measured: Boolean get() = before != null && after != null
}
