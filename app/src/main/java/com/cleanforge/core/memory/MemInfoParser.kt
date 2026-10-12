package com.cleanforge.core.memory

/**
 * Pure parser for /proc/meminfo. No I/O here so it is trivially testable.
 *
 * Format reminder:
 *   MemTotal:        3925568 kB
 *   MemAvailable:    1456784 kB
 *   ...
 * Values are in kB. HugePages_* are counts (no kB) and are ignored.
 */
object MemInfoParser {

    private const val PROC_MEMINFO = "/proc/meminfo"

    fun parse(content: String, timestamp: Long): RamSnapshot? {
        val bytes = mutableMapOf<String, Long>()

        for (line in content.lineSequence()) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue

            val key = line.substring(0, colon).trim()
            var value = line.substring(colon + 1).trim()

            // Strip the trailing "kB" if present.
            val multiplier = if (value.endsWith("kB")) {
                value = value.removeSuffix("kB").trim()
                1024L
            } else {
                1L
            }

            val number = value.toLongOrNull() ?: continue
            bytes[key] = number * multiplier
        }

        // A zero or negative total is corrupt input: a snapshot built from it would read as CRITICAL pressure.
        val total = bytes["MemTotal"]?.takeIf { it > 0L } ?: return null

        val free = bytes["MemFree"] ?: 0L
        val buffers = bytes["Buffers"] ?: 0L
        val cached = bytes["Cached"] ?: 0L

        // MemAvailable exists on every kernel Android 11 ships with, but fall back
        // to a conservative estimate if a device omits it.
        val available = bytes["MemAvailable"] ?: (free + buffers + cached)

        return RamSnapshot(
            totalBytes = total,
            availableBytes = available.coerceAtMost(total),
            freeBytes = free,
            buffersBytes = buffers,
            cachedBytes = cached,
            swapTotalBytes = bytes["SwapTotal"] ?: 0L,
            swapFreeBytes = bytes["SwapFree"] ?: 0L,
            timestamp = timestamp
        )
    }

    val path: String get() = PROC_MEMINFO
}