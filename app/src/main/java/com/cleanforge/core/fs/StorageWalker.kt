package com.cleanforge.core.fs

/**
 * Bounded, non-recursive (explicit stack) directory walker.
 *  - never follows symlinks (uses lstat);
 *  - never crosses a mount point (st_dev must equal the root's);
 *  - stops at maxDepth / maxEntries instead of running away.
 */
class StorageWalker(private val fs: FsOps) {

    data class Entry(val path: String, val name: String, val stat: FsStat, val depth: Int)

    fun walk(
        root: String,
        limits: WalkLimits = WalkLimits(),
        skipDir: (String) -> Boolean = { false },
        onTruncated: () -> Unit = {},
        /** Called once per directory that exists but could not be listed (its contents are NOT part of the walk). */
        onUnreadable: () -> Unit = {}
    ): Sequence<Entry> = sequence {
        val rootStat = fs.lstat(root)
        if (rootStat == null || rootStat.type != FsType.DIRECTORY) return@sequence

        val stack = ArrayDeque<Pair<String, Int>>()
        stack.addLast(root to 0)
        var count = 0

        while (stack.isNotEmpty()) {
            val (dir, depth) = stack.removeLast()
            val names = fs.list(dir)
            if (names == null) {
                onUnreadable()
                continue
            }
            for (name in names) {
                if (!isPlainName(name)) continue
                count++
                if (count > limits.maxEntries) {
                    onTruncated()
                    return@sequence
                }
                val path = "$dir/$name"
                val st = fs.lstat(path) ?: continue
                if (st.dev != rootStat.dev) continue // mount boundary
                yield(Entry(path, name, st, depth + 1))
                if (st.type == FsType.DIRECTORY && !skipDir(path)) {
                    if (depth + 1 < limits.maxDepth) {
                        stack.addLast(path to depth + 1)
                    } else {
                        onTruncated()
                    }
                }
            }
        }
    }

    /**
     * Sum of regular-file sizes below [root]. Null if [root] is not a readable directory/file.
     * Unreadable sub-directories are counted in [SizeResult.unreadable] instead of being skipped silently.
     */
    fun sizeOf(root: String, limits: WalkLimits = WalkLimits()): SizeResult? {
        val st = fs.lstat(root) ?: return null
        if (st.type == FsType.FILE) return SizeResult(st.size, true)
        if (st.type != FsType.DIRECTORY) return null
        var total = 0L
        var complete = true
        var unreadable = 0
        for (e in walk(root, limits, onTruncated = { complete = false }, onUnreadable = { unreadable++ })) {
            if (e.stat.type == FsType.FILE) total += e.stat.size
        }
        return SizeResult(total, complete, unreadable)
    }

    companion object {
        /** A directory-entry name that cannot escape its parent. */
        fun isPlainName(name: String): Boolean =
            name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\u0000' }
    }
}
