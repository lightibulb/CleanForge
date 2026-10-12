package com.cleanforge.core.scanner

import com.cleanforge.core.model.ScanItem
import com.cleanforge.core.privileged.PrivilegedOperation
import kotlinx.coroutines.flow.Flow

enum class Requirement { NONE, ALL_FILES_ACCESS, SHIZUKU }

/** QUICK scanners run on "Scan"; ON_DEMAND ones are heavier and only run when the user asks. */
enum class ScanMode { QUICK, ON_DEMAND }

/**
 * A scanner CLASSIFIES: every item it emits must carry the evidence that justifies it.
 * Filename, extension, or directory name alone is never accepted as evidence.
 */
interface Scanner {
    val id: String
    val displayName: String
    val requirement: Requirement
    val mode: ScanMode

    /** Privileged operations this scanner needs when [requirement] is [Requirement.SHIZUKU]. */
    val privilegedOperations: Set<PrivilegedOperation> get() = emptySet()

    /** Emits batches so memory stays bounded on low-end devices. */
    fun scan(): Flow<List<ScanItem>>

    /**
     * Why the result of the LAST completed [scan] may be incomplete: a failed listing, skipped items, a lower-bound
     * size. Empty means nothing was left out. An empty result with a non-empty reason list is "could not check", never
     * "nothing found". Read it after the flow has completed.
     */
    fun incompleteReasons(): List<String> = emptyList()
}

internal const val BATCH_SIZE = 50
internal const val DAY_MS = 24L * 60L * 60L * 1000L
