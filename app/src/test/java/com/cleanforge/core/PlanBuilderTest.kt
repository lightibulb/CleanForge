package com.cleanforge.core

import com.cleanforge.core.cleaning.PlanBuilder
import com.cleanforge.core.fs.FsStat
import com.cleanforge.core.fs.FsType
import com.cleanforge.core.model.CleaningCategory
import com.cleanforge.core.model.ConfidenceLevel
import com.cleanforge.core.model.RiskLevel
import com.cleanforge.core.safety.SafetyValidator
import com.cleanforge.testutil.ROOT
import com.cleanforge.testutil.scanItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlanBuilderTest {
    private val builder = PlanBuilder(SafetyValidator())
    private val stat = FsStat(FsType.FILE, 10, 1, 1, 1)

    @Test fun `refused items are kept with their reason and never planned`() {
        val bad = scanItem("$ROOT/Download/../../etc/passwd")
        val good = scanItem("$ROOT/Download/a.bin")
        val plan = builder.build(listOf(bad, good), listOf(bad, good))
        assertThat(plan.items).containsExactly(good)
        assertThat(plan.rejected).hasSize(1)
        assertThat(plan.rejected[0].reason).isNotEmpty()
    }

    @Test fun `an item inside a folder that is also selected is dropped as covered`() {
        // Both are individually valid; the second lies inside the first folder's contents-only clean.
        val folder = scanItem("$ROOT/DCIM/.thumbnails", CleaningCategory.THUMBNAIL)
        val inner = scanItem("$ROOT/DCIM/.thumbnails/t1.jpg", CleaningCategory.LARGE_FILE)
        val plan = builder.build(listOf(folder, inner), listOf(folder, inner))
        assertThat(plan.items).containsExactly(folder)
        assertThat(plan.rejected.map { it.item }).containsExactly(inner)
        assertThat(plan.rejected[0].reason).contains("covered")
    }

    @Test fun `same path selected twice is planned once`() {
        val a = scanItem("$ROOT/Download/a.bin")
        val plan = builder.build(listOf(a, a), listOf(a))
        assertThat(plan.items).hasSize(1)
        assertThat(plan.rejected).hasSize(1)
    }

    // ---------- duplicates: the copy that stays is whichever one the user did NOT tick ----------

    private fun copy(path: String, ino: Long, group: String = "g1") = scanItem(
        path, CleaningCategory.DUPLICATE, ConfidenceLevel.MEDIUM, RiskLevel.ELEVATED,
        fingerprint = FsStat(FsType.FILE, 10, 1, 1, ino), groupId = group
    )

    @Test fun `deleting the OLDEST copy is allowed and the unticked copy is pinned as the keeper`() {
        val oldest = copy("$ROOT/Pictures/family-photo.jpg", ino = 1)   // scanner lists the oldest first
        val newer = copy("$ROOT/Download/family-photo.jpg", ino = 2)
        val plan = builder.build(selected = listOf(oldest), scanned = listOf(oldest, newer))
        assertThat(plan.items.map { it.path }).containsExactly(oldest.path)
        assertThat(plan.items[0].keepPath).isEqualTo(newer.path)
        assertThat(plan.items[0].keepFingerprint).isEqualTo(newer.fingerprint)
        assertThat(plan.rejected).isEmpty()
    }

    @Test fun `deleting the newer copy instead pins the oldest as the keeper`() {
        val oldest = copy("$ROOT/Pictures/a.jpg", ino = 1)
        val newer = copy("$ROOT/Download/a.jpg", ino = 2)
        val plan = builder.build(selected = listOf(newer), scanned = listOf(oldest, newer))
        assertThat(plan.items.map { it.path }).containsExactly(newer.path)
        assertThat(plan.items[0].keepPath).isEqualTo(oldest.path)
    }

    @Test fun `every copy of a group selected means every one is refused`() {
        val a = copy("$ROOT/Pictures/a.jpg", ino = 1)
        val b = copy("$ROOT/Download/a.jpg", ino = 2)
        val plan = builder.build(selected = listOf(a, b), scanned = listOf(a, b))
        assertThat(plan.items).isEmpty()
        assertThat(plan.rejected.map { it.item.path }).containsExactly(a.path, b.path)
        assertThat(plan.rejected.all { it.reason.contains("at least one must stay") }).isTrue()
    }

    @Test fun `in a group of three, ticking two leaves the third as keeper for both`() {
        val a = copy("$ROOT/Pictures/a.jpg", ino = 1)
        val b = copy("$ROOT/Download/a.jpg", ino = 2)
        val c = copy("$ROOT/Movies/a.jpg", ino = 3)
        val plan = builder.build(selected = listOf(a, b), scanned = listOf(a, b, c))
        assertThat(plan.items.map { it.path }).containsExactly(a.path, b.path)
        assertThat(plan.items.map { it.keepPath }.toSet()).containsExactly(c.path)
    }

    @Test fun `a duplicate whose other copies are unknown is refused`() {
        val a = copy("$ROOT/Pictures/a.jpg", ino = 1)
        val plan = builder.build(selected = listOf(a), scanned = listOf(a))
        assertThat(plan.items).isEmpty()
        assertThat(plan.rejected).hasSize(1)
    }

    @Test fun `a duplicate without a group is refused`() {
        val lone = scanItem("$ROOT/Download/a.jpg", CleaningCategory.DUPLICATE, fingerprint = stat)
        assertThat(builder.build(listOf(lone), listOf(lone)).items).isEmpty()
    }

    @Test fun `another category cannot be used to delete every copy of the same content`() {
        val a = copy("$ROOT/Pictures/a.mp4", ino = 1)
        val b = copy("$ROOT/Download/a.mp4", ino = 2)
        // The user ticked both files through the "large files" list.
        val bigA = scanItem(a.path, CleaningCategory.LARGE_FILE, fingerprint = a.fingerprint)
        val bigB = scanItem(b.path, CleaningCategory.LARGE_FILE, fingerprint = b.fingerprint)
        val plan = builder.build(selected = listOf(bigA, bigB), scanned = listOf(bigA, bigB, a, b))
        assertThat(plan.items).isEmpty()
        assertThat(plan.rejected.map { it.item.path }).containsExactly(a.path, b.path)
    }

    @Test fun `a path flagged as both large file and duplicate is planned as the duplicate, keeper protection included`() {
        val a = copy("$ROOT/Pictures/a.mp4", ino = 1)
        val b = copy("$ROOT/Download/a.mp4", ino = 2)
        val bigA = scanItem(a.path, CleaningCategory.LARGE_FILE, fingerprint = a.fingerprint)
        val plan = builder.build(selected = listOf(bigA, a), scanned = listOf(bigA, a, b))
        assertThat(plan.items).hasSize(1)
        assertThat(plan.items[0].category).isEqualTo(CleaningCategory.DUPLICATE)
        assertThat(plan.items[0].keepPath).isEqualTo(b.path)
    }
}
