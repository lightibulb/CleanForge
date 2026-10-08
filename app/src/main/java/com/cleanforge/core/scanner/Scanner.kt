package com.cleanforge.core.scanner

import com.cleanforge.core.model.ScanItem
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

    /** Emits batches so memory stays bounded on low-end devices. */
    fun scan(): Flow<List<ScanItem>>
}

internal const val BATCH_SIZE = 50
internal const val DAY_MS = 24L * 60L * 60L * 1000L
