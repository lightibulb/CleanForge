package com.cleanforge.core.cleaning

import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.model.CleaningCategory
import com.cleanforge.core.model.CleaningPlan
import com.cleanforge.core.model.RejectedItem
import com.cleanforge.core.model.ScanItem
import com.cleanforge.core.safety.SafetyValidator
import javax.inject.Inject

/** VALIDATE + PLAN. Turns the user's selection into a plan; refused items are kept WITH their reason. */
class PlanBuilder @Inject constructor(private val validator: SafetyValidator) {

    /**
     * @param selected the items the user ticked.
     * @param scanned every item of the current scan. Needed to know the OTHER copies of a duplicate group,
     *   because the copy that stays is whichever one the user did not tick, never one CleanForge guessed.
     */
    fun build(selected: List<ScanItem>, scanned: List<ScanItem>): CleaningPlan {
        val rejected = ArrayList<RejectedItem>()
        val valid = ArrayList<ScanItem>()
        val seen = HashSet<String>()

        // If one path was flagged twice (say as a large file AND as a duplicate), plan the duplicate form:
        // it is the one that carries the "a copy must stay" protection. (sortedBy is stable.)
        val ordered = selected.sortedBy { if (it.category == CleaningCategory.DUPLICATE) 0 else 1 }
        for (item in ordered) {
            val reason = validator.rejectionReason(item)
            when {
                reason != null -> rejected += RejectedItem(item, reason)
                !seen.add(item.path) -> rejected += RejectedItem(item, "Selected twice")
                else -> valid += item
            }
        }

        // Nothing may be deleted both as part of a folder and on its own.
        val folderRoots = valid
            .filter { it.deleteMode == DeleteMode.TREE || it.deleteMode == DeleteMode.CONTENTS_ONLY }
            .map { it.path }
        val afterOverlap = ArrayList<ScanItem>()
        for (item in valid) {
            val parent = folderRoots.firstOrNull { it != item.path && item.path.startsWith("$it/") }
            if (parent != null) rejected += RejectedItem(item, "Already covered by $parent") else afterOverlap += item
        }

        // A duplicate may only be removed while a copy the user did NOT tick is guaranteed to survive.
        // Everything that is a candidate counts as "going away", even if it is refused further down: that can
        // only make the rule stricter, never looser.
        val deleting = afterOverlap.mapTo(HashSet<String>()) { it.path }
        val copiesByGroup: Map<String, List<ScanItem>> = scanned
            .filter { it.category == CleaningCategory.DUPLICATE && it.groupId != null }
            .groupBy { it.groupId ?: "" }

        val planned = ArrayList<ScanItem>()
        for (item in afterOverlap) {
            if (item.category != CleaningCategory.DUPLICATE) {
                planned += item
                continue
            }
            val copies = item.groupId?.let { copiesByGroup[it] }.orEmpty()
            val survivor = copies.firstOrNull { it.path != item.path && it.path !in deleting && it.fingerprint != null }
            if (survivor == null) {
                val why = if (copies.size < 2) {
                    "The other copies of this file are unknown; scan again"
                } else {
                    "Every copy of this file is selected; at least one must stay"
                }
                rejected += RejectedItem(item, why)
                continue
            }
            planned += item.copy(keepPath = survivor.path, keepFingerprint = survivor.fingerprint)
        }

        // The same rule must hold when the copies were ticked through some OTHER category (e.g. large files):
        // never plan the deletion of every copy of the same content.
        val plannedPaths = planned.mapTo(HashSet<String>()) { it.path }
        val wholeGroupPaths = HashSet<String>()
        for (copies in copiesByGroup.values) {
            if (copies.all { it.path in plannedPaths }) copies.mapTo(wholeGroupPaths) { it.path }
        }
        val finalItems = ArrayList<ScanItem>()
        for (item in planned) {
            if (item.path in wholeGroupPaths) {
                rejected += RejectedItem(item, "Every copy of this file is selected; at least one must stay")
            } else {
                finalItems += item
            }
        }
        return CleaningPlan(finalItems, rejected)
    }
}
