package com.cleanforge.ui

import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.model.CleaningCategory
import com.cleanforge.core.model.ConfidenceLevel
import com.cleanforge.core.model.RiskLevel
import com.cleanforge.testutil.ROOT
import com.cleanforge.testutil.scanItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The destructive confirmation dialog renders exactly [confirmationEvidence]; these tests pin down what it must contain.
 * (A Compose UI test would need an emulator; this checks the data the dialog is built from, on the JVM.)
 */
class ConfirmationEvidenceTest {

    private fun cache(pkg: String) =
        scanItem("$ROOT/Android/data/$pkg/cache", CleaningCategory.APP_CACHE, ConfidenceLevel.HIGH, RiskLevel.SAFE, pkg, shizuku = true)

    @Test fun `items that share a file name are still told apart by full path and owner`() {
        val a = cache("com.first.app")
        val b = cache("com.second.app")
        assertThat(a.displayName).isEqualTo(b.displayName) // the premise: a bare name ("cache") is ambiguous

        val ea = a.confirmationEvidence()
        val eb = b.confirmationEvidence()
        assertThat(ea.path).isEqualTo("$ROOT/Android/data/com.first.app/cache")
        assertThat(eb.path).isEqualTo("$ROOT/Android/data/com.second.app/cache")
        assertThat(ea.path).isNotEqualTo(eb.path)
        assertThat(ea.owningPackage).isEqualTo("com.first.app")
        assertThat(eb.owningPackage).isEqualTo("com.second.app")
    }

    @Test fun `every item shows why it was flagged, its confidence and its risk`() {
        val items = listOf(
            cache("com.example"),
            scanItem("$ROOT/Download/big.mkv", CleaningCategory.LARGE_FILE, ConfidenceLevel.UNKNOWN, RiskLevel.DANGEROUS),
            scanItem("$ROOT/Android/data/com.gone", CleaningCategory.CORPSE_LEFTOVER, ConfidenceLevel.MEDIUM, RiskLevel.ELEVATED)
        )
        for (item in items) {
            val e = item.confirmationEvidence()
            assertThat(e.path).isEqualTo(item.path)
            assertThat(e.whyFlagged).isEqualTo(item.reasonForFlagging)
            assertThat(e.whyFlagged).isNotEmpty()
            assertThat(e.confidence).isEqualTo(item.confidence.name)
            assertThat(e.risk).isEqualTo(item.risk.name)
            assertThat(e.category).isNotEmpty()
            assertThat(e.whatHappens).isNotEmpty()
        }
        val big = items[1].confirmationEvidence()
        assertThat(big.confidence).isEqualTo("UNKNOWN")
        assertThat(big.risk).isEqualTo("DANGEROUS")
        assertThat(big.flags).contains("No undo")
    }

    @Test fun `a duplicate names the copy that stays and the copy that goes`() {
        val keep = "$ROOT/Pictures/a.jpg"
        val dup = scanItem(
            "$ROOT/Download/a.jpg", CleaningCategory.DUPLICATE, ConfidenceLevel.MEDIUM, RiskLevel.ELEVATED, keepPath = keep
        ).confirmationEvidence()
        assertThat(dup.keepPath).isEqualTo(keep)
        assertThat(dup.path).isEqualTo("$ROOT/Download/a.jpg")
        assertThat(dup.path).isNotEqualTo(dup.keepPath)
    }

    @Test fun `only duplicates carry a keeper line`() {
        val large = scanItem("$ROOT/Download/x.bin", CleaningCategory.LARGE_FILE, keepPath = "$ROOT/Download/y.bin")
        assertThat(large.confirmationEvidence().keepPath).isNull()
    }

    @Test fun `what will happen is spelled out per delete mode`() {
        fun what(mode: DeleteMode) =
            scanItem("$ROOT/Download/x", CleaningCategory.LARGE_FILE, mode = mode).confirmationEvidence().whatHappens
        val all = DeleteMode.entries.map { what(it) }
        assertThat(all.toSet()).hasSize(DeleteMode.entries.size) // all four descriptions differ
        assertThat(what(DeleteMode.CONTENTS_ONLY)).contains("folder itself stays")
        assertThat(what(DeleteMode.TREE)).contains("everything inside it")
    }

    @Test fun `shizuku removal is disclosed`() {
        assertThat(cache("com.example").confirmationEvidence().flags).contains("Removed through Shizuku")
    }
}
