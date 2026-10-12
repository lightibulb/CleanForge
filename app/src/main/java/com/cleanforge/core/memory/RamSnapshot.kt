package com.cleanforge.core.memory

/** Coarse signal for the UI. Based on AVAILABLE memory, not "used", because Android caches intentionally. */
enum class MemoryPressure { LOW, NORMAL, HIGH, CRITICAL }

/**
 * One honest reading of system memory. Source of truth is /proc/meminfo.
 * Nothing here claims that "high usage" is bad by itself.
 */
data class RamSnapshot(
    val totalBytes: Long,
    val availableBytes: Long,
    val freeBytes: Long,
    val buffersBytes: Long,
    val cachedBytes: Long,
    val swapTotalBytes: Long,
    val swapFreeBytes: Long,
    val timestamp: Long
) {
    /** Memory the system cannot hand out right now. */
    val usedBytes: Long get() = (totalBytes - availableBytes).coerceAtLeast(0L)

    val usedFraction: Float get() = if (totalBytes > 0) usedBytes.toFloat() / totalBytes else 0f
    val availableFraction: Float get() = if (totalBytes > 0) availableBytes.toFloat() / totalBytes else 0f

    val pressure: MemoryPressure
        get() = when {
            availableFraction <= 0.05f -> MemoryPressure.CRITICAL
            availableFraction <= 0.12f -> MemoryPressure.HIGH
            availableFraction <= 0.30f -> MemoryPressure.NORMAL
            else -> MemoryPressure.LOW
        }
}