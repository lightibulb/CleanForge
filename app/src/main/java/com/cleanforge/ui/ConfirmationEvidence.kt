package com.cleanforge.ui

import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.model.CleaningCategory
import com.cleanforge.core.model.Reversibility
import com.cleanforge.core.model.ScanItem

/**
 * Everything the user needs to recognise ONE item in the destructive confirmation: the FULL path (a bare file
 * name such as "cache" is ambiguous), why it was flagged, the evidence strength and risk, what exactly will
 * happen to it, and for duplicates which copy stays.
 *
 * Pure Kotlin on purpose: the confirmation dialog renders exactly this, and unit tests can check it without a device.
 */
data class ConfirmationEvidence(
    val path: String,
    val category: String,
    val whyFlagged: String,
    /** HIGH / MEDIUM / LOW / UNKNOWN */
    val confidence: String,
    /** SAFE / ELEVATED / DANGEROUS */
    val risk: String,
    val sizeBytes: Long,
    val whatHappens: String,
    val owningPackage: String?,
    /** Only for duplicates: the copy that will stay. Null otherwise. */
    val keepPath: String?,
    val flags: List<String>
)

fun ScanItem.confirmationEvidence(): ConfirmationEvidence = ConfirmationEvidence(
    path = path,
    category = category.label,
    whyFlagged = reasonForFlagging,
    confidence = confidence.name,
    risk = risk.name,
    sizeBytes = sizeBytes,
    whatHappens = when (deleteMode) {
        DeleteMode.FILE -> "Deletes this file"
        DeleteMode.EMPTY_DIR -> "Deletes this folder (only if it is empty)"
        DeleteMode.CONTENTS_ONLY -> "Deletes everything inside this folder; the folder itself stays"
        DeleteMode.TREE -> "Deletes this folder and everything inside it"
    },
    owningPackage = owningPackage,
    keepPath = if (category == CleaningCategory.DUPLICATE) keepPath else null,
    flags = buildList {
        if (reversibility == Reversibility.NOT_REVERSIBLE) add("No undo")
        if (requiresShizuku) add("Removed through Shizuku")
    }
)
