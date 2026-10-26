package com.cleanforge.core.history

object SnapshotCodec {
    private const val MAGIC = "CleanForge-snapshot v1"

    fun encode(s: Snapshot): String = buildString {
        append(MAGIC).append('\n')
        append("meta\ttimestamp\t").append(s.timestamp).append('\n')
        append("meta\trootPath\t").append(sanitize(s.rootPath)).append('\n')
        append("meta\ttotalBytes\t").append(s.totalBytes).append('\n')
        append("meta\tfreeBytes\t").append(s.freeBytes).append('\n')
        append("meta\tincomplete\t").append(if (s.incomplete) "1" else "0").append('\n')
        s.categoryBytes.forEach { (k, v) -> append("cat\t").append(sanitize(k)).append('\t').append(v).append('\n') }
        s.dirs.forEach { d ->
            append("dir\t").append(sanitize(d.path)).append('\t')
                .append(sanitize(d.parentPath)).append('\t')
                .append(sanitize(d.name)).append('\t')
                .append(d.depth).append('\t')
                .append(d.bytes).append('\t')
                .append(d.fileCount).append('\n')
        }
    }

    fun decode(text: String): Snapshot? {
        val lines = text.lineSequence().toList()
        if (lines.isEmpty() || lines[0] != MAGIC) return null
        var ts = 0L; var root = ""; var total = 0L; var free = 0L; var incomplete = false
        val cats = LinkedHashMap<String, Long>()
        val dirs = ArrayList<SnapshotEntry>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val p = line.split('\t')
            when (p.getOrNull(0)) {
                "meta" -> {
                    val k = p.getOrNull(1) ?: return null
                    val v = p.getOrNull(2) ?: return null
                    when (k) {
                        "timestamp" -> ts = v.toLongOrNull() ?: return null
                        "rootPath" -> root = v
                        "totalBytes" -> total = v.toLongOrNull() ?: return null
                        "freeBytes" -> free = v.toLongOrNull() ?: return null
                        "incomplete" -> incomplete = v == "1"
                        else -> return null
                    }
                }
                "cat" -> {
                    val k = p.getOrNull(1) ?: return null
                    val v = p.getOrNull(2)?.toLongOrNull() ?: return null
                    cats[k] = v
                }
                "dir" -> {
                    if (p.size < 7) return null
                    val depth = p[4].toIntOrNull() ?: return null
                    val bytes = p[5].toLongOrNull() ?: return null
                    val count = p[6].toIntOrNull() ?: return null
                    dirs += SnapshotEntry(p[1], p[2], p[3], depth, bytes, count)
                }
                else -> return null
            }
        }
        return Snapshot(ts, root, total, free, dirs, cats, incomplete)
    }

    private fun sanitize(s: String): String =
        s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}
