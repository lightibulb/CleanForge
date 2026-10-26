package com.cleanforge.ui.treemap

data class BoxSize(val width: Float, val height: Float)

data class TreemapItem(val key: String, val label: String, val bytes: Long)

data class TreemapCell(
    val item: TreemapItem,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

object SquarifiedTreemap {
    fun layout(items: List<TreemapItem>, bounds: BoxSize): List<TreemapCell> {
        val filtered = items.filter { it.bytes > 0 }
            .sortedWith(compareByDescending<TreemapItem> { it.bytes }.thenBy { it.key })
        if (filtered.isEmpty() || bounds.width <= 0f || bounds.height <= 0f) return emptyList()

        val total = filtered.sumOf { it.bytes }.toDouble()
        val area = bounds.width.toDouble() * bounds.height.toDouble()
        var remaining = filtered.map { it to (it.bytes.toDouble() / total * area) }
        val cells = ArrayList<TreemapCell>(filtered.size)

        var x = 0.0; var y = 0.0
        var w = bounds.width.toDouble(); var h = bounds.height.toDouble()

        while (remaining.isNotEmpty() && w > 1e-6 && h > 1e-6) {
            val shortSide = minOf(w, h)
            val row = ArrayList<Pair<TreemapItem, Double>>()
            var i = 0
            while (i < remaining.size) {
                val candidate = remaining[i]
                val withIt = worst(row + candidate, shortSide)
                val without = if (row.isEmpty()) Double.MAX_VALUE else worst(row, shortSide)
                if (row.isNotEmpty() && withIt > without) break
                row += candidate
                i++
            }
            val rowArea = row.sumOf { it.second }
            if (w >= h) {
                val colW = rowArea / h
                var cy = y
                for ((item, a) in row) {
                    val cellH = a / colW
                    cells += TreemapCell(item, x.toFloat(), cy.toFloat(), colW.toFloat(), cellH.toFloat())
                    cy += cellH
                }
                x += colW; w -= colW
            } else {
                val rowH = rowArea / w
                var cx = x
                for ((item, a) in row) {
                    val cellW = a / rowH
                    cells += TreemapCell(item, cx.toFloat(), y.toFloat(), cellW.toFloat(), rowH.toFloat())
                    cx += cellW
                }
                y += rowH; h -= rowH
            }
            remaining = remaining.drop(row.size)
        }
        return cells
    }

    private fun worst(row: List<Pair<TreemapItem, Double>>, shortSide: Double): Double {
        if (row.isEmpty()) return Double.MAX_VALUE
        val sum = row.sumOf { it.second }
        if (sum <= 0.0) return Double.MAX_VALUE
        val maxA = row.maxOf { it.second }
        val minA = row.minOf { it.second }
        val s2 = shortSide * shortSide
        val sum2 = sum * sum
        return maxOf(s2 * maxA / sum2, sum2 / (s2 * minA))
    }
}
