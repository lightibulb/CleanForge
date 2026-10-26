package com.cleanforge.core.inventory

import com.cleanforge.core.fs.FsOps
import com.cleanforge.core.fs.FsProbe
import com.cleanforge.core.fs.FsType
import com.cleanforge.core.fs.StorageWalker
import com.cleanforge.core.fs.WalkLimits
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.PriorityQueue

class StorageIndexer(
    private val fs: FsOps,
    private val maxDepth: Int = 3,
    private val maxDirs: Int = 20_000,
    private val topFiles: Int = 100,
    private val limits: WalkLimits = WalkLimits(maxDepth = 32, maxEntries = 200_000)
) {
    private class Agg(
        val path: String,
        val parentPath: String,
        val name: String,
        val depth: Int,
        var newestMtime: Long
    ) {
        var bytes: Long = 0L
        var fileCount: Int = 0
    }

    suspend fun index(
        rootPath: String,
        roots: List<String>,
        totalBytes: Long,
        freeBytes: Long,
        now: () -> Long = { System.currentTimeMillis() }
    ): StorageIndex {
        val rootLevel = rootPath.count { it == '/' }
        val aggs = HashMap<String, Agg>()
        val categoryBytes = HashMap<String, LongArray>()
        val heap = PriorityQueue<FileEntry>(compareBy { it.bytes })
        var truncated = false
        var dirsCapped = false
        var scanned = 0L

        for (rel in roots) {
            currentCoroutineContext().ensureActive()
            val dir = if (rel.startsWith("/")) rel else "$rootPath/$rel"
            when (val p = fs.probe(dir)) {
                is FsProbe.Found ->
                    if (p.stat.type == FsType.DIRECTORY) {
                        ensureDir(aggs, rootPath, dir, p.stat.mtimeMs, maxDirs) { dirsCapped = true }
                    }
                else -> Unit
            }
            for (e in StorageWalker(fs).walk(
                dir,
                limits,
                onTruncated = { truncated = true },
                onUnreadable = { truncated = true }
            )) {
                if (++scanned % 256L == 0L) currentCoroutineContext().ensureActive()
                if (e.stat.type != FsType.FILE) {
                    if (e.path.count { it == '/' } - rootLevel <= maxDepth) {
                        ensureDir(aggs, rootPath, e.path, e.stat.mtimeMs, maxDirs) { dirsCapped = true }
                    } else {
                        truncated = true
                    }
                    continue
                }
                var parent = e.path.substringBeforeLast('/')
                var parentDepth = e.path.count { it == '/' } - rootLevel - 1
                while (parentDepth >= 1) {
                    if (parentDepth <= maxDepth) {
                        ensureDir(aggs, rootPath, parent, e.stat.mtimeMs, maxDirs) { dirsCapped = true }?.let { a ->
                            a.bytes += e.stat.size
                            a.fileCount += 1
                            if (e.stat.mtimeMs > a.newestMtime) a.newestMtime = e.stat.mtimeMs
                        }
                    } else {
                        truncated = true
                    }
                    parent = parent.substringBeforeLast('/')
                    parentDepth--
                }
                val cat = TypeClassifier.classify(e.name)
                val arr = categoryBytes.getOrPut(cat) { LongArray(2) }
                arr[0] += e.stat.size
                arr[1] += 1
                heap.add(FileEntry(e.path, e.name, e.stat.size, e.stat.mtimeMs, cat))
                if (heap.size > topFiles) heap.poll()
            }
        }

        val dirs = aggs.values.map { a ->
            DirNode(
                path = a.path,
                parentPath = a.parentPath,
                name = a.name,
                depth = a.depth,
                bytes = a.bytes,
                fileCount = a.fileCount,
                newestMtime = a.newestMtime,
                complete = !truncated,
                unreadable = 0
            )
        }.sortedWith(compareBy({ it.depth }, { it.path }))

        val cats = categoryBytes.map { (k, v) ->
            CategoryTotal(k, TypeClassifier.label(k), v[0], v[1].toInt(), complete = !truncated)
        }.sortedByDescending { it.bytes }

        return StorageIndex(
            timestamp = now(),
            rootPath = rootPath,
            totalBytes = totalBytes,
            freeBytes = freeBytes,
            directories = dirs,
            largestFiles = heap.sortedByDescending { it.bytes },
            categoryTotals = cats,
            incomplete = truncated || dirsCapped
        )
    }

    private fun ensureDir(
        aggs: HashMap<String, Agg>,
        rootPath: String,
        path: String,
        mtime: Long,
        maxDirs: Int,
        onCap: () -> Unit
    ): Agg? {
        aggs[path]?.let { return it }
        if (aggs.size >= maxDirs) { onCap(); return null }
        val name = path.substringAfterLast('/')
        val parent = path.substringBeforeLast('/', "")
        val depth = path.count { it == '/' } - rootPath.count { it == '/' }
        val a = Agg(path, parent, name, depth, mtime)
        aggs[path] = a
        return a
    }
}
