package com.cleanforge.core

import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.model.CleaningCategory
import com.cleanforge.core.safety.SafetyValidator
import com.cleanforge.testutil.ROOT
import com.cleanforge.testutil.scanItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The FORENSIC_MEDIA allowlist: exact per-package folders, single direct-child files, never anything else. */
class ForensicValidatorTest {
    private val validator = SafetyValidator()
    private val wa = "$ROOT/Android/media/com.whatsapp/WhatsApp"

    private fun reason(path: String, pkg: String? = "com.whatsapp", mode: DeleteMode? = null) =
        validator.rejectionReason(scanItem(path, CleaningCategory.FORENSIC_MEDIA, pkg = pkg, mode = mode))

    @Test fun `allowlisted whatsapp files under Android media are accepted`() {
        assertThat(reason("$wa/Media/.Statuses/a.mp4")).isNull()
        assertThat(reason("$wa/Media/WhatsApp Video/Sent/v.mp4")).isNull()
        assertThat(reason("$wa/Media/WhatsApp Images/Sent/i.jpg")).isNull()
        assertThat(reason("$wa/Media/WhatsApp Animated Gifs/Sent/g.mp4")).isNull()
        assertThat(reason("$wa/Databases/msgstore-2026-08-01.1.db.crypt14")).isNull()
        assertThat(reason("$ROOT/Android/media/com.whatsapp.w4b/WhatsApp/Media/.Statuses/a.mp4", pkg = "com.whatsapp.w4b")).isNull()
        assertThat(reason("$ROOT/Android/media/org.telegram.messenger/Telegram/Telegram Video/v.mp4", pkg = "org.telegram.messenger")).isNull()
    }

    @Test fun `received media, other folders and the live backup are refused`() {
        assertThat(reason("$wa/Media/WhatsApp Images/IMG-1.jpg")).isNotNull()
        assertThat(reason("$wa/Media/WhatsApp Images/Private/i.jpg")).isNotNull()
        assertThat(reason("$wa/Media/WhatsApp Documents/Sent/d.pdf")).isNotNull()
        assertThat(reason("$wa/Databases/msgstore.db.crypt14")).isNotNull()
        assertThat(reason("$wa/Databases/wa.db")).isNotNull()
        assertThat(reason("$ROOT/Android/media/org.telegram.messenger/Telegram/Telegram Stories/s.mp4", pkg = "org.telegram.messenger")).isNotNull()
    }

    @Test fun `the folder itself and anything nested deeper are refused`() {
        assertThat(reason("$wa/Media/.Statuses")).isNotNull()
        assertThat(reason("$wa/Media/.Statuses/nested/a.mp4")).isNotNull()
        assertThat(reason("$wa/Databases/sub/msgstore-2026.db.crypt14")).isNotNull()
    }

    @Test fun `an item without a package, or with a different package than its folder, is refused`() {
        assertThat(reason("$wa/Media/.Statuses/a.mp4", pkg = null)).isNotNull()
        assertThat(reason("$wa/Media/.Statuses/a.mp4", pkg = "com.other.app")).isNotNull()
    }

    @Test fun `unknown packages and other Android folders are refused`() {
        assertThat(reason("$ROOT/Android/media/com.example.app/WhatsApp/Media/.Statuses/a.mp4", pkg = "com.example.app")).isNotNull()
        assertThat(reason("$ROOT/Android/data/com.whatsapp/files/x.bin")).isNotNull()
        assertThat(reason("$ROOT/Android/obb/com.whatsapp/main.obb")).isNotNull()
    }

    @Test fun `forensic items are refused inside personal folders`() {
        assertThat(reason("$ROOT/DCIM/Camera/a.jpg")).isNotNull()
        assertThat(reason("$ROOT/Download/a.mp4")).isNotNull()
    }

    @Test fun `forensic items are single files only`() {
        assertThat(reason("$wa/Media/.Statuses/a.mp4", mode = DeleteMode.TREE)).isNotNull()
        assertThat(reason("$wa/Media/.Statuses/a.mp4", mode = DeleteMode.CONTENTS_ONLY)).isNotNull()
    }

    @Test fun `matching is case-insensitive like shared storage but the package must match exactly`() {
        assertThat(reason("$ROOT/android/MEDIA/com.whatsapp/whatsapp/media/.statuses/a.mp4")).isNull()
    }

    @Test fun `tiktok twitter and instagram files under Android data are accepted only in their exact folders`() {
        assertThat(reason("$ROOT/Android/data/com.zhiliaoapp.musically/cache/a.bin", pkg = "com.zhiliaoapp.musically")).isNull()
        assertThat(reason("$ROOT/Android/data/com.zhiliaoapp.musically/files/.music/a.mp3", pkg = "com.zhiliaoapp.musically")).isNull()
        assertThat(reason("$ROOT/Android/data/com.zhiliaoapp.musically/files/other/a.bin", pkg = "com.zhiliaoapp.musically")).isNotNull()
        assertThat(reason("$ROOT/Android/data/com.twitter.android/cache/a.bin", pkg = "com.twitter.android")).isNull()
        assertThat(reason("$ROOT/Android/data/com.instagram.android/databases/a.db", pkg = "com.instagram.android")).isNotNull()
    }

    @Test fun `a differently cased package folder is not the allowlisted package`() {
        assertThat(reason("$ROOT/Android/media/com.WhatsApp/WhatsApp/Media/.Statuses/a.mp4", pkg = "com.WhatsApp")).isNotNull()
    }
}
