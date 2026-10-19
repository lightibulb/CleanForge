package com.cleanforge.core.privileged

import java.io.File

/** One running app process: its process name and how much RAM it holds (resident set size, includes shared pages). */
data class ProcessMemoryEntry(val pid: Int, val uid: Int, val rssKb: Long, val processName: String)

/** Where process facts come from. The real one reads /proc inside the Shizuku service; the JVM tests use a fake. */
interface ProcSource {
    fun processIds(): List<Int>
    fun read(pid: Int, name: String): String?
}

object AndroidProcSource : ProcSource {
    override fun processIds(): List<Int> = File("/proc").list()?.mapNotNull { it.toIntOrNull() }.orEmpty()

    override fun read(pid: Int, name: String): String? = try {
        File("/proc/$pid/$name").inputStream().use { String(it.readBytes(), Charsets.UTF_8) }
    } catch (e: Exception) {
        null
    }
}

/**
 * Reads and (de)serialises the process list. Read-only, takes no path from the caller, and only ever returns APP
 * processes (uid >= 10000): no system or root process is reported.
 */
object ProcessMemoryReader {
    const val APP_UID_START = 10_000
    const val MAX_ENTRIES = 600
    private const val MAX_NAME = 200

    fun read(src: ProcSource): List<ProcessMemoryEntry> {
        val out = ArrayList<ProcessMemoryEntry>()
        for (pid in src.processIds()) {
            val status = src.read(pid, "status") ?: continue
            val uid = field(status, "Uid:")?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull() ?: continue
            if (uid < APP_UID_START) continue
            val rssKb = field(status, "VmRSS:")?.removeSuffix("kB")?.trim()?.toLongOrNull() ?: continue
            val name = src.read(pid, "cmdline")?.substringBefore('\u0000')?.trim().orEmpty()
            if (name.isEmpty() || name.length > MAX_NAME || name.any { it == '|' || it < ' ' }) continue
            out += ProcessMemoryEntry(pid, uid, rssKb, name)
            if (out.size >= MAX_ENTRIES) break
        }
        return out
    }

    fun encode(entries: List<ProcessMemoryEntry>): Array<String> =
        entries.map { "${it.pid}|${it.uid}|${it.rssKb}|${it.processName}" }.toTypedArray()

    /** Tolerant: a malformed line is dropped, never thrown. */
    fun decode(lines: Array<String>?): List<ProcessMemoryEntry> {
        if (lines == null) return emptyList()
        val out = ArrayList<ProcessMemoryEntry>()
        for (line in lines) {
            if (out.size >= MAX_ENTRIES) break
            val p = line.split('|', limit = 4)
            if (p.size != 4) continue
            val pid = p[0].toIntOrNull() ?: continue
            val uid = p[1].toIntOrNull() ?: continue
            val rss = p[2].toLongOrNull() ?: continue
            if (rss < 0 || p[3].isEmpty()) continue
            out += ProcessMemoryEntry(pid, uid, rss, p[3])
        }
        return out
    }

    private fun field(status: String, key: String): String? =
        status.lineSequence().firstOrNull { it.startsWith(key) }?.substring(key.length)?.trim()
}
