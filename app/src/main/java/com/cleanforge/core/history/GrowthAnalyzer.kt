package com.cleanforge.core.history

import com.cleanforge.core.inventory.TypeClassifier

data class GrowthRow(
    val path: String,
    val name: String,
    val previousBytes: Long,
    val currentBytes: Long,
    val deltaBytes: Long,
    val percentChange: Float?
)

data class GrowthReport(
    val from: Snapshot,
    val to: Snapshot,
    val categories: List<GrowthRow>,
    val directories: List<GrowthRow>,
    val lowerBound: Boolean
)

object GrowthAnalyzer {
    fun compare(from: Snapshot, to: Snapshot, topDirs: Int = 50): GrowthReport {
        val fromDirs = from.dirs.associateBy { it.path }
        val toDirs = to.dirs.associateBy { it.path }
        val dirRows = (fromDirs.keys + toDirs.keys).map { path ->
            val p = fromDirs[path]
            val c = toDirs[path]
            val ref = c ?: p!!
            val prev = p?.bytes ?: 0L
            val cur = c?.bytes ?: 0L
            GrowthRow(path, ref.name, prev, cur, cur - prev,
                if (prev > 0) (cur - prev).toFloat() / prev else null)
        }.sortedByDescending { it.deltaBytes }.take(topDirs)

        val fromCats = from.categoryBytes
        val toCats = to.categoryBytes
        val catRows = (fromCats.keys + toCats.keys).map { k ->
            val prev = fromCats[k] ?: 0L
            val cur = toCats[k] ?: 0L
            GrowthRow(k, TypeClassifier.label(k), prev, cur, cur - prev,
                if (prev > 0) (cur - prev).toFloat() / prev else null)
        }.sortedByDescending { it.deltaBytes }

        return GrowthReport(from, to, catRows, dirRows, from.incomplete || to.incomplete)
    }
}
