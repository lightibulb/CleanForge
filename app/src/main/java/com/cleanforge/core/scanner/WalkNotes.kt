package com.cleanforge.core.scanner

import com.cleanforge.core.fs.StorageWalker
import com.cleanforge.core.fs.WalkLimits

/**
 * Collects why a filesystem walk may have missed something, for [Scanner.incompleteReasons]. An empty result with
 * reasons means "could not check everything", never "nothing found".
 */
internal class WalkNotes {
    private var wasTruncated = false
    private var unreadable = 0

    fun onTruncated() {
        wasTruncated = true
    }

    fun onUnreadable() {
        unreadable++
    }

    fun reasons(): List<String> = buildList {
        if (wasTruncated) add("A folder is deeper or larger than the scan limit; some files were not checked")
        if (unreadable > 0) {
            add("$unreadable item(s) could not be read, or sit on another storage volume, and were skipped")
        }
    }
}

/** [StorageWalker.walk] that records truncation and unreadable entries in [notes]. */
internal fun StorageWalker.walkNoting(
    root: String,
    notes: WalkNotes,
    skipDir: (String) -> Boolean = { false },
    limits: WalkLimits = WalkLimits()
): Sequence<StorageWalker.Entry> = walk(root, limits, skipDir, notes::onTruncated, notes::onUnreadable)
