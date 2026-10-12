package com.cleanforge.core.model

import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.fs.FsStat

/** Apps whose data is personal by nature: their items are never pre-selected. */
object ProtectedApps {
    val MESSAGING: Set<String> = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.messenger.web",
        "org.telegram.plus", "org.thoughtcrime.securesms", "com.facebook.orca", "com.instagram.android",
        "com.snapchat.android", "com.viber.voip", "com.google.android.apps.messaging"
    )
}

/** How much to trust [ScanItem.sizeBytes]. A size is never an estimate: it is measured, or a measured lower bound. */
enum class SizeQuality {
    /** Complete walk, nothing unreadable. Logical bytes (sum of file sizes), not disk blocks. */
    MEASURED,

    /** At least this much: the walk hit a safety bound or some sub-folders could not be read. */
    LOWER_BOUND
}

/**
 * Result of CLASSIFY. A ScanItem is a *proposal with evidence*, never a deletion command:
 * it only becomes a deletion after VALIDATE -> PLAN -> USER CONFIRMATION -> REVALIDATE.
 */
data class ScanItem(
    val path: String,
    val sizeBytes: Long,
    val category: CleaningCategory,
    /** Evidence-based explanation shown to the user: WHY this is considered removable. */
    val reasonForFlagging: String,
    val confidence: ConfidenceLevel,
    val risk: RiskLevel,
    val owningPackage: String? = null,
    val requiresShizuku: Boolean = false,
    val deleteMode: DeleteMode = DeleteMode.FILE,
    val reversibility: Reversibility = Reversibility.NOT_REVERSIBLE,
    val lastModified: Long = 0L,
    /** Group identifier (duplicate sets). */
    val groupId: String? = null,
    /** lstat snapshot taken during the scan; compared again immediately before deletion. */
    val fingerprint: FsStat? = null,
    /** For duplicates: the copy that stays. It must still exist, unchanged, at deletion time. */
    val keepPath: String? = null,
    val keepFingerprint: FsStat? = null,
    val sizeQuality: SizeQuality = SizeQuality.MEASURED,
    /** Directories inside the item that could not be listed while measuring (their bytes are not in [sizeBytes]). */
    val unreadableCount: Int = 0
) {
    val displayName: String get() = path.substringAfterLast('/')

    /**
     * Pre-ticked in the review list. Deliberately narrow: only well-evidenced app caches
     * that are not messaging apps. Everything else must be ticked by the user.
     */
    val isPreselected: Boolean
        get() = category == CleaningCategory.APP_CACHE &&
            confidence == ConfidenceLevel.HIGH &&
            risk == RiskLevel.SAFE &&
            owningPackage !in ProtectedApps.MESSAGING
}
