package com.cleanforge.core

import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.model.CleaningCategory
import com.cleanforge.core.model.ConfidenceLevel
import com.cleanforge.core.model.RiskLevel
import com.cleanforge.core.safety.SafetyValidator
import com.cleanforge.testutil.ROOT
import com.cleanforge.testutil.scanItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SafetyValidatorTest {
    private val v = SafetyValidator()

    private fun refused(path: String, category: CleaningCategory = CleaningCategory.LARGE_FILE, pkg: String? = null) =
        v.rejectionReason(scanItem(path, category, pkg = pkg))

    // ---- lexical attacks ----

    @Test fun `parent traversal is rejected`() {
        assertThat(refused("$ROOT/Download/../../etc/passwd")).isNotNull()
        assertThat(refused("$ROOT/Download/a/../b.txt")).isNotNull()
    }

    @Test fun `relative empty NUL control trailing and doubled slashes are rejected`() {
        assertThat(refused("Download/a.txt")).isNotNull()
        assertThat(refused("")).isNotNull()
        assertThat(refused("$ROOT/Download/a\u0000.txt")).isNotNull()
        assertThat(refused("$ROOT/Download/a\n.txt")).isNotNull()
        assertThat(refused("$ROOT/Download/a.txt/")).isNotNull()
        assertThat(refused("$ROOT//Download/a.txt")).isNotNull()
        assertThat(refused("$ROOT/Download/./a.txt")).isNotNull()
    }

    @Test fun `over-long path is rejected`() {
        assertThat(refused("$ROOT/Download/" + "a".repeat(5000))).isNotNull()
    }

    @Test fun `sdcard and self-primary aliases are rejected (regression - they bypassed the DCIM protection)`() {
        assertThat(refused("/sdcard/DCIM/Camera/IMG_1.jpg")).isNotNull()
        assertThat(refused("/storage/self/primary/DCIM/Camera/IMG_1.jpg")).isNotNull()
    }

    @Test fun `system paths and the storage root are rejected`() {
        for (p in listOf("/", "/system/bin/sh", "/data/data/com.x", "/storage", "/storage/emulated", ROOT, "$ROOT/")) {
            assertThat(refused(p)).isNotNull()
        }
    }

    @Test fun `top-level storage folders themselves are rejected`() {
        for (d in listOf("DCIM", "Download", "Pictures", "Movies", "Music", "Documents", "Android", "SomeApp")) {
            assertThat(refused("$ROOT/$d")).isNotNull()
        }
    }

    @Test fun `dots inside a legitimate file name are fine`() {
        assertThat(refused("$ROOT/Download/my..file.zip")).isNull()
    }

    @Test fun `unicode spaces and symbols are accepted`() {
        assertThat(refused("$ROOT/Download/ファイル 名前 é #1 (copy).apk", CleaningCategory.APK_FILE)).isNull()
    }

    // ---- Android/ folder rules ----

    @Test fun `Android data obb and media folders cannot be deleted directly`() {
        for (p in listOf("$ROOT/Android/data", "$ROOT/Android/obb", "$ROOT/Android/media",
            "$ROOT/Android/data/com.x/files", "$ROOT/Android/media/com.whatsapp/WhatsApp/Media/a.jpg")) {
            assertThat(refused(p)).isNotNull()
        }
    }

    @Test fun `app cache is accepted only as contents-only of the exact cache folder`() {
        val ok = scanItem("$ROOT/Android/data/com.example/cache", CleaningCategory.APP_CACHE, pkg = "com.example")
        assertThat(v.rejectionReason(ok)).isNull()
        assertThat(v.rejectionReason(ok.copy(deleteMode = DeleteMode.TREE))).isNotNull()
        assertThat(v.rejectionReason(ok.copy(deleteMode = DeleteMode.FILE))).isNotNull()
        assertThat(v.rejectionReason(ok.copy(owningPackage = "com.other"))).isNotNull()
        assertThat(v.rejectionReason(ok.copy(path = "$ROOT/Android/data/com.example/files"))).isNotNull()
        assertThat(v.rejectionReason(ok.copy(path = "$ROOT/Android/data/com.example/cache/sub"))).isNotNull()
    }

    @Test fun `structural names are matched case-insensitively like the real filesystem`() {
        val item = scanItem("$ROOT/android/DATA/com.example/CACHE", CleaningCategory.APP_CACHE, pkg = "com.example")
        assertThat(v.rejectionReason(item)).isNull()
        // and the protected zones too:
        val personal = scanItem("$ROOT/dcim/Camera/IMG.jpg", CleaningCategory.APP_CACHE, pkg = "com.example")
        assertThat(v.rejectionReason(personal)).isNotNull()
    }

    @Test fun `corpse is accepted only as the exact package folder`() {
        val ok = scanItem("$ROOT/Android/obb/com.gone.game", CleaningCategory.CORPSE_LEFTOVER, pkg = "com.gone.game")
        assertThat(v.rejectionReason(ok)).isNull()
        assertThat(v.rejectionReason(ok.copy(path = "$ROOT/Android/obb/not a package"))).isNotNull()
        assertThat(v.rejectionReason(ok.copy(path = "$ROOT/Android/obb/.nomedia"))).isNotNull()
        assertThat(v.rejectionReason(ok.copy(deleteMode = DeleteMode.FILE))).isNotNull()
    }

    // ---- personal data ----

    @Test fun `cache rules never apply inside personal folders`() {
        for (zone in listOf("DCIM", "Pictures", "Movies", "Music", "Documents", "Download", "WhatsApp", "Telegram")) {
            val item = scanItem("$ROOT/$zone/Camera", CleaningCategory.APP_CACHE, pkg = "com.example")
            assertThat(v.rejectionReason(item)).isNotNull()
        }
    }

    @Test fun `a single reviewed file inside a personal folder may be deleted`() {
        // The user explicitly picked it in Review; it is a plain FILE-mode item and never pre-selected.
        assertThat(refused("$ROOT/DCIM/Camera/IMG_1.jpg")).isNull()
        assertThat(refused("$ROOT/Movies/clip.mp4", CleaningCategory.DUPLICATE)).isNull()
    }

    @Test fun `a personal-folder item that claims to be pre-selected is rejected`() {
        val sneaky = scanItem(
            "$ROOT/Download/x", CleaningCategory.APP_CACHE,
            confidence = ConfidenceLevel.HIGH, risk = RiskLevel.SAFE, pkg = "com.example"
        )
        assertThat(v.rejectionReason(sneaky)).isNotNull()
    }

    @Test fun `thumbnail folder is accepted only as the exact legacy path`() {
        val ok = scanItem("$ROOT/DCIM/.thumbnails", CleaningCategory.THUMBNAIL)
        assertThat(v.rejectionReason(ok)).isNull()
        assertThat(v.rejectionReason(ok.copy(path = "$ROOT/DCIM/Camera"))).isNotNull()
        assertThat(v.rejectionReason(ok.copy(deleteMode = DeleteMode.TREE))).isNotNull()
    }

    @Test fun `empty dir category only allows rmdir mode`() {
        val ok = scanItem("$ROOT/Download/empty", CleaningCategory.EMPTY_DIRECTORY)
        assertThat(v.rejectionReason(ok)).isNull()
        assertThat(v.rejectionReason(ok.copy(deleteMode = DeleteMode.TREE))).isNotNull()
    }

    // ---- pre-selection is deliberately narrow ----

    @Test fun `only evidenced non-messaging app caches are pre-selected`() {
        val cache = scanItem("$ROOT/Android/data/com.example/cache", CleaningCategory.APP_CACHE,
            confidence = ConfidenceLevel.HIGH, risk = RiskLevel.SAFE, pkg = "com.example")
        assertThat(cache.isPreselected).isTrue()
        assertThat(cache.copy(owningPackage = "com.whatsapp").isPreselected).isFalse()
        assertThat(cache.copy(confidence = ConfidenceLevel.MEDIUM).isPreselected).isFalse()
        for (cat in CleaningCategory.entries.filter { it != CleaningCategory.APP_CACHE }) {
            val i = scanItem("$ROOT/Download/x", cat, confidence = ConfidenceLevel.HIGH, risk = RiskLevel.SAFE)
            assertThat(i.isPreselected).isFalse()
        }
    }
}
