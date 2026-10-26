package com.cleanforge.core.inventory

data class CategoryTotal(
    val key: String,
    val label: String,
    val bytes: Long,
    val fileCount: Int,
    val complete: Boolean
)

data class FileEntry(
    val path: String,
    val name: String,
    val bytes: Long,
    val mtime: Long,
    val category: String
)

data class StorageIndex(
    val timestamp: Long,
    val rootPath: String,
    val totalBytes: Long,
    val freeBytes: Long,
    val directories: List<DirNode>,
    val largestFiles: List<FileEntry>,
    val categoryTotals: List<CategoryTotal>,
    val incomplete: Boolean
) {
    fun childrenOf(parentPath: String): List<DirNode> =
        directories.filter { it.parentPath == parentPath }.sortedByDescending { it.bytes }

    fun byPath(path: String): DirNode? = directories.firstOrNull { it.path == path }

    companion object {
        val EMPTY = StorageIndex(0L, "", 0L, 0L, emptyList(), emptyList(), emptyList(), true)
    }
}
