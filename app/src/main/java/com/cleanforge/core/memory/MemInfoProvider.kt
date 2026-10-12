package com.cleanforge.core.memory

import java.io.File

/** Abstraction so tests can inject fake content and the UI can fake pressure states. */
interface MemoryInfoProvider {
    /** Null if memory info could not be read. Callers must treat this as "unknown", never zero. */
    fun snapshot(): RamSnapshot?
}

/**
 * Reads /proc/meminfo directly. No permission required; the file is world-readable.
 * Deliberately NOT routed through FsOps (that surface is scoped to shared storage).
 */
class ProcMemInfoProvider(
    private val reader: () -> String? = DEFAULT_READER,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) : MemoryInfoProvider {

    override fun snapshot(): RamSnapshot? {
        val content = reader() ?: return null
        return MemInfoParser.parse(content, timeProvider())
    }

    companion object {
        private val DEFAULT_READER: () -> String? = {
            try {
                File(MemInfoParser.path).readText()
            } catch (e: Exception) {
                null
            }
        }
    }
}