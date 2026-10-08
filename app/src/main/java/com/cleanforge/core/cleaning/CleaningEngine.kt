package com.cleanforge.core.cleaning

import com.cleanforge.core.backend.Capabilities
import com.cleanforge.core.backend.FileOperationBackend
import com.cleanforge.core.backend.MIN_PLAUSIBLE_PACKAGES
import com.cleanforge.core.backend.PackageInventory
import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.fs.DeleteStatus
import com.cleanforge.core.model.CleaningCategory
import com.cleanforge.core.model.CleaningReport
import com.cleanforge.core.model.ConfirmedPlan
import com.cleanforge.core.model.ItemOutcome
import com.cleanforge.core.model.ItemStatus
import com.cleanforge.core.model.ScanItem
import com.cleanforge.core.safety.SafetyValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

sealed interface CleaningProgress {
    data class Started(val total: Int) : CleaningProgress
    data class Progress(val done: Int, val total: Int, val freedBytes: Long, val currentName: String) : CleaningProgress
    data class Completed(val report: CleaningReport) : CleaningProgress
}

/**
 * Executes a [ConfirmedPlan]. Pipeline per item:
 *   REVALIDATE (policy + live capabilities + fresh evidence + fingerprint) -> DELETE -> VERIFY -> REPORT.
 * Sequential on purpose: gentle on a low-end eMMC and trivially cancellable between items.
 * Nothing in here accepts a ScanItem list directly; a human-confirmed plan is the only entry point.
 */
class CleaningEngine(
    private val safety: SafetyValidator,
    private val standardBackend: FileOperationBackend,
    private val shizukuBackend: FileOperationBackend,
    private val inventory: PackageInventory,
    private val capabilities: Capabilities
) {

    fun execute(confirmed: ConfirmedPlan): Flow<CleaningProgress> = flow {
        val items = confirmed.plan.items
        val outcomes = ArrayList<ItemOutcome>(items.size)
        var freed = 0L
        emit(CleaningProgress.Started(items.size))
        for ((index, item) in items.withIndex()) {
            currentCoroutineContext().ensureActive()
            val outcome = processItem(item)
            outcomes += outcome
            freed += outcome.bytesFreed
            emit(CleaningProgress.Progress(index + 1, items.size, freed, item.displayName))
        }
        emit(CleaningProgress.Completed(CleaningReport(outcomes)))
    }.flowOn(Dispatchers.IO)

    private suspend fun processItem(item: ScanItem): ItemOutcome {
        // ---- REVALIDATE ----
        val refusal = safety.rejectionReason(item)
        if (refusal != null) return skipped(item, "Safety check failed: $refusal")

        if (item.requiresShizuku) {
            if (!capabilities.shizukuAuthorized()) return skipped(item, "Shizuku is not available right now")
        } else if (!capabilities.hasAllFilesAccess()) {
            return skipped(item, "All-files access is no longer granted")
        }
        val backend = if (item.requiresShizuku) shizukuBackend else standardBackend

        if (item.category == CleaningCategory.CORPSE_LEFTOVER || item.category == CleaningCategory.APP_CACHE) {
            val pkg = item.owningPackage ?: return skipped(item, "No package recorded for this folder")
            val known = inventory.knownPackageNames()
            if (known == null || known.size < MIN_PLAUSIBLE_PACKAGES) {
                return skipped(item, "Could not verify which apps are installed")
            }
            // Two different questions: "known" includes data-kept uninstalls, "installed" does not.
            val installed = inventory.isCurrentlyInstalled(pkg)
            if (item.category == CleaningCategory.CORPSE_LEFTOVER && (pkg in known || installed)) {
                return skipped(item, "$pkg is known to Android again; its folder is no longer a leftover")
            }
            if (item.category == CleaningCategory.APP_CACHE && (!installed || pkg !in known)) {
                return skipped(item, "$pkg is not currently installed")
            }
        }

        if (item.category == CleaningCategory.DUPLICATE) {
            if (item.keepPath == item.path) return skipped(item, "The copy that should stay is this very file")
            val keepNow = item.keepPath?.let { backend.lstat(it) }
            val keepThen = item.keepFingerprint
            if (keepNow == null || keepThen == null || !keepNow.sameObjectAs(keepThen)) {
                return skipped(item, "The copy that should stay changed or disappeared")
            }
        }

        val expected = item.fingerprint ?: return skipped(item, "No scan snapshot for this item; scan again")
        val now = backend.lstat(item.path) ?: return skipped(item, "Already gone")
        if (!now.sameObjectAs(expected)) return skipped(item, "Changed since the scan; scan again to include it")

        // ---- DELETE ----
        val result = try {
            backend.delete(item.path, item.deleteMode, expected)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return failed(item, t.message ?: "Delete failed unexpectedly")
        }

        // ---- VERIFY ----
        val after = backend.lstat(item.path)
        val verified = when (item.deleteMode) {
            DeleteMode.FILE, DeleteMode.EMPTY_DIR, DeleteMode.TREE -> after == null
            DeleteMode.CONTENTS_ONLY -> after != null && backend.list(item.path)?.isEmpty() == true
        }

        return when {
            result.status == DeleteStatus.OK && verified ->
                ItemOutcome(item, ItemStatus.DELETED, "Deleted", result.bytesFreed)
            result.status == DeleteStatus.OK ->
                ItemOutcome(item, ItemStatus.FAILED, "Reported success but verification found leftovers", result.bytesFreed)
            result.status == DeleteStatus.MISSING || result.status == DeleteStatus.IDENTITY_CHANGED ||
                result.status == DeleteStatus.WRONG_TYPE || result.status == DeleteStatus.REFUSED ->
                skipped(item, result.detail.ifEmpty { result.status.name })
            result.status == DeleteStatus.PARTIAL ->
                ItemOutcome(item, ItemStatus.FAILED, "Partly cleaned: ${result.detail}", result.bytesFreed)
            else -> failed(item, "${result.status}: ${result.detail}")
        }
    }

    private fun skipped(item: ScanItem, why: String) = ItemOutcome(item, ItemStatus.SKIPPED, why)
    private fun failed(item: ScanItem, why: String) = ItemOutcome(item, ItemStatus.FAILED, why)
}
