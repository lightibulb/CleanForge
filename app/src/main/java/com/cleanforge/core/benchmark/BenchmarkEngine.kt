package com.cleanforge.core.benchmark

import com.cleanforge.core.fs.FsOps
import com.cleanforge.core.fs.FsType
import com.cleanforge.core.fs.StorageWalker
import com.cleanforge.core.fs.WalkLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

data class BenchmarkSample(val timestampMs: Long, val rssBytes: Long)
data class BenchmarkAccuracy(val expectedEntries: Long?, val actualEntries: Long, val match: Boolean)

data class BenchmarkReport(
    val startedAt: Long,
    val durationMs: Long,
    val entriesVisited: Long,
    val bytesSeen: Long,
    val peakRssBytes: Long,
    val samples: List<BenchmarkSample>,
    val accuracy: BenchmarkAccuracy,
    val truncated: Boolean
) {
    val entriesPerSecond: Long get() = if (durationMs > 0) entriesVisited * 1000L / durationMs else 0L
}

class BenchmarkEngine(private val fs: FsOps) {
    suspend fun run(
        root: String,
        limits: WalkLimits = WalkLimits(maxDepth = 32, maxEntries = 200_000),
        expectedEntries: Long? = null,
        sampleEveryMs: Long = 250L
    ): BenchmarkReport = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        var entries = 0L
        var bytes = 0L
        var truncated = false
        var unreadable = 0
        val sampler = RssSampler(sampleEveryMs).also { it.start() }
        try {
            for (e in StorageWalker(fs).walk(
                root, limits,
                onTruncated = { truncated = true },
                onUnreadable = { unreadable++ }
            )) {
                entries++
                if (e.stat.type == FsType.FILE) bytes += e.stat.size
                if (entries % 512L == 0L) currentCoroutineContext().ensureActive()
            }
        } finally {
            sampler.stop()
        }
        BenchmarkReport(
            startedAt = startedAt,
            durationMs = System.currentTimeMillis() - startedAt,
            entriesVisited = entries,
            bytesSeen = bytes,
            peakRssBytes = sampler.peak(),
            samples = sampler.samples(),
            accuracy = BenchmarkAccuracy(expectedEntries, entries, expectedEntries == null || entries == expectedEntries),
            truncated = truncated || unreadable > 0
        )
    }

    private class RssSampler(private val everyMs: Long) {
        private val _samples = ArrayList<BenchmarkSample>()
        private val peak = AtomicLong(0)
        @Volatile private var running = false
        private var thread: Thread? = null

        fun start() {
            running = true
            thread = Thread {
                while (running) {
                    val ts = System.currentTimeMillis()
                    val rss = rssBytes()
                    synchronized(_samples) { _samples += BenchmarkSample(ts, rss) }
                    peak.updateAndGet { maxOf(it, rss) }
                    try { Thread.sleep(everyMs) } catch (e: InterruptedException) { break }
                }
            }.also { it.isDaemon = true; it.start() }
        }

        fun stop() { running = false; thread?.interrupt(); thread?.join(500) }
        fun peak(): Long = peak.get()
        fun samples(): List<BenchmarkSample> = synchronized(_samples) { _samples.toList() }

        /** Android: real RSS from /proc/self/statm. JVM: 0. Never throws. */
        private fun rssBytes(): Long {
            val android = runCatching {
                val f = File("/proc/self/statm")
                if (!f.canRead()) null else f.readText().trim().split(' ').getOrNull(1)?.toLongOrNull()?.times(4096L)
            }.getOrNull()
            if (android != null && android > 0L) return android
            // JVM fallback returns 0 (PolicyGuardTest forbids shell commands to keep the no shell rule).
            return 0L
        }
    }
}
