package com.cleanforge.core.history

import java.io.File

interface SnapshotStore {
    fun loadAll(): List<Snapshot>
    fun save(snapshot: Snapshot)
    fun prune(maxCount: Int = 20, maxAgeMs: Long = 90L * 24 * 60 * 60 * 1000)
}

class FileSnapshotStore(
    private val dir: File,
    private val maxFileBytes: Long = 2L * 1024 * 1024
) : SnapshotStore {
    override fun loadAll(): List<Snapshot> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".snapshot") }
            ?.sortedBy { it.name }
            ?.mapNotNull { f -> runCatching { SnapshotCodec.decode(f.readText()) }.getOrNull() }
            ?: emptyList()
    }

    override fun save(snapshot: Snapshot) {
        dir.mkdirs()
        val text = SnapshotCodec.encode(snapshot)
        if (text.length > maxFileBytes) return
        runCatching { File(dir, "${snapshot.timestamp}.snapshot").writeText(text) }
    }

    override fun prune(maxCount: Int, maxAgeMs: Long) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".snapshot") }?.sortedBy { it.name } ?: return
        val cutoff = System.currentTimeMillis() - maxAgeMs
        val keep = files.takeLast(maxCount.coerceAtLeast(1)).toHashSet()
        for (f in files) {
            val ts = f.name.removeSuffix(".snapshot").toLongOrNull() ?: Long.MAX_VALUE
            if (f !in keep || ts < cutoff) runCatching { f.delete() }
        }
    }
}

class InMemorySnapshotStore(private var snapshots: List<Snapshot> = emptyList()) : SnapshotStore {
    override fun loadAll(): List<Snapshot> = snapshots.sortedBy { it.timestamp }
    override fun save(snapshot: Snapshot) {
        snapshots = snapshots.filter { it.timestamp != snapshot.timestamp } + snapshot
    }
    override fun prune(maxCount: Int, maxAgeMs: Long) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        snapshots = snapshots.filter { it.timestamp >= cutoff }.takeLast(maxCount.coerceAtLeast(1))
    }
}
