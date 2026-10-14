package com.cleanforge.core.memory

/**
 * Result of the honest clear action. freedBytes is a MEASURED delta in MemAvailable,
 * never an estimate or a made-up number. It may legitimately be ~0.
 */
data class ClearMemoryResult(
    val processesTouched: Int,
    val freedBytes: Long,
    val before: RamSnapshot?,
    val after: RamSnapshot?,
    /** Truthful copy shown to the user. Never claims a "boost". */
    val note: String
)