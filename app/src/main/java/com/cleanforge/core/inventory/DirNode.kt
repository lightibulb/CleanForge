package com.cleanforge.core.inventory

data class DirNode(
    val path: String,
    val parentPath: String,
    val name: String,
    val depth: Int,
    val bytes: Long,
    val fileCount: Int,
    val newestMtime: Long,
    val complete: Boolean,
    val unreadable: Int
) {
    val isLowerBound: Boolean get() = !complete || unreadable > 0
}
