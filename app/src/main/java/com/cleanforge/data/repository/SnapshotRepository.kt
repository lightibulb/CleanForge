package com.cleanforge.data.repository

import com.cleanforge.core.history.GrowthAnalyzer
import com.cleanforge.core.history.GrowthReport
import com.cleanforge.core.history.Snapshot
import com.cleanforge.core.history.SnapshotEntry
import com.cleanforge.core.history.SnapshotStore
import com.cleanforge.core.inventory.StorageIndex
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SnapshotRepository @Inject constructor(private val store: SnapshotStore) {

    fun all(): List<Snapshot> = store.loadAll()
    fun latest(): Snapshot? = store.loadAll().lastOrNull()

    fun saveFrom(index: StorageIndex) {
        if (index.rootPath.isEmpty()) return
        val entries = index.directories.map { d ->
            SnapshotEntry(d.path, d.parentPath, d.name, d.depth, d.bytes, d.fileCount)
        }
        val cats = index.categoryTotals.associate { it.key to it.bytes }
        store.save(
            Snapshot(
                timestamp = index.timestamp,
                rootPath = index.rootPath,
                totalBytes = index.totalBytes,
                freeBytes = index.freeBytes,
                dirs = entries,
                categoryBytes = cats,
                incomplete = index.incomplete
            )
        )
        store.prune()
    }

    fun growth(fromTs: Long, toTs: Long, topDirs: Int = 50): GrowthReport? {
        val snapshots = store.loadAll()
        val from = snapshots.firstOrNull { it.timestamp == fromTs } ?: return null
        val to = snapshots.firstOrNull { it.timestamp == toTs } ?: return null
        return GrowthAnalyzer.compare(from, to, topDirs)
    }
}
