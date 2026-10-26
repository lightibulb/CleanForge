package com.cleanforge.core.history

data class SnapshotEntry(
    val path: String,
    val parentPath: String,
    val name: String,
    val depth: Int,
    val bytes: Long,
    val fileCount: Int
)

data class Snapshot(
    val timestamp: Long,
    val rootPath: String,
    val totalBytes: Long,
    val freeBytes: Long,
    val dirs: List<SnapshotEntry>,
    val categoryBytes: Map<String, Long>,
    val incomplete: Boolean
)
