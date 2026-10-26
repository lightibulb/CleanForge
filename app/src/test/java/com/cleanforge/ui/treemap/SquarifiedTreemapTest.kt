package com.cleanforge.ui.treemap

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SquarifiedTreemapTest {
    @Test fun `cell areas sum to the bounds area`() {
        val items = listOf(
            TreemapItem("a", "A", 5), TreemapItem("b", "B", 3),
            TreemapItem("c", "C", 1), TreemapItem("d", "D", 1)
        )
        val bounds = BoxSize(400f, 300f)
        val cells = SquarifiedTreemap.layout(items, bounds)
        val totalArea = cells.sumOf { (it.width * it.height).toDouble() }
        assertThat(totalArea).isWithin(0.5).of((400 * 300).toDouble())
        assertThat(cells.map { it.item.key }).containsExactly("a", "b", "c", "d")
    }

    @Test fun `larger items get larger cells and zero-byte items are dropped`() {
        val items = listOf(TreemapItem("big", "Big", 90), TreemapItem("small", "Small", 10), TreemapItem("zero", "Zero", 0))
        val cells = SquarifiedTreemap.layout(items, BoxSize(200f, 200f))
        val big = cells.first { it.item.key == "big" }
        val small = cells.first { it.item.key == "small" }
        assertThat(big.width * big.height).isGreaterThan(small.width * small.height)
        assertThat(cells.any { it.item.key == "zero" }).isFalse()
    }

    @Test fun `deterministic order for equal sizes`() {
        val items = listOf(TreemapItem("b", "B", 1), TreemapItem("a", "A", 1))
        val c1 = SquarifiedTreemap.layout(items, BoxSize(100f, 100f)).map { it.item.key }
        val c2 = SquarifiedTreemap.layout(items.reversed(), BoxSize(100f, 100f)).map { it.item.key }
        assertThat(c1).isEqualTo(c2)
    }

    @Test fun `empty and zero bounds return empty`() {
        assertThat(SquarifiedTreemap.layout(emptyList(), BoxSize(100f, 100f))).isEmpty()
        assertThat(SquarifiedTreemap.layout(listOf(TreemapItem("a", "A", 1)), BoxSize(0f, 100f))).isEmpty()
    }
}
