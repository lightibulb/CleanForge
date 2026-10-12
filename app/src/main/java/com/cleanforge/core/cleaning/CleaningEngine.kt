package com.cleanforge.core.cleaning

import com.cleanforge.core.backend.Capabilities
import com.cleanforge.core.backend.FileOperationBackend
import com.cleanforge.core.backend.FsResult
import com.cleanforge.core.backend.InstallState
import com.cleanforge.core.backend.MIN_PLAUSIBLE_PACKAGES
import com.cleanforge.core.backend.PackageInventory
import com.cleanforge.core.model.CleaningCategory
import com.cleanforge.core.model.CleaningReport
import com.cleanforge.core.model.ConfirmedPlan
import com.cleanforge.core.model.ItemOutcome
import com.cleanforge.core.model.ItemStatus
import com.cleanforge.core.model.ScanItem
import com.cleanforge.core.privileged.FailureKind
import com.cleanforge.core.privileged.MaintenanceResult
import com.cleanforge.core.privileged.PrivilegedExecutor
import com.cleanforge.core.privileged.classifyDeletion
import com.cleanforge.core.privileged.observePostState
import com.cleanforge.core.privileged.toMaintenanceRequest
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

    /** Emitted BEFORE an item is touched, so a stop request can say which item was in flight. */
    data class ItemStarted(val index: Int, val total: Int, val item: ScanItem) : CleaningProgress

    /** [outcome] is the finished item's result; the UI keeps them so a stopped run still has an accurate report. */
    data class Progress(
        val done: Int,
        val total: Int,
        val freedBytes: Long,
        val currentName: String,
        val outcome: ItemOutcome? = null
    ) : CleaningProgress

    data class Completed(val report: CleaningReport) : CleaningProgress
}

/**
 * Executes a [ConfirmedPlan]. Pipeline per item:
 *   REVALIDATE (policy + live capabilities + fresh evidence + fingerprint) -> DELETE -> VERIFY -> REPORT.
 * Sequential on purpose: gentle on a low-end eMMC and trivially cancellable between items.
 * Nothing in here accepts a ScanItem list directly; a human-confirmed plan is the only entry point.
 *
 * Privileged items (Android/data caches, uninstalled-app leftovers) go through [PrivilegedExecutor] as typed
 * maintenance requests; everything else runs in this process through [standardBackend]. An item that cannot be mapped
 * to a supported privileged operation is skipped with a reason, never run as some weaker operation.
 */
class CleaningEngine(
    private val safety: SafetyValidator,
    private val standardBackend: FileOperationBackend,
    private val privileged: PrivilegedExecutor,
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
            emit(CleaningProgress.ItemStarted(index + 1, items.size, item))
            val outcome = processItem(item)
            outcomes += outcome
            freed += outcome.bytesFreed
            emit(CleaningProgress.Progress(index + 1, items.size, freed, item.displayName, outcome))
        }
        emit(CleaningProgress.Completed(CleaningReport(outcomes)))
    }.flowOn(Dispatchers.IO)

    private suspend fun processItem(item: ScanItem): ItemOutcome {
        // ---- REVALIDATE ----
        val refusal = safety.rejectionReason(item)
        if (refusal != null) return skipped(item, "Safety check failed: $refusal")

        if (!item.requiresShizuku && !capabilities.hasAllFilesAccess()) {
            return skipped(item, "All-files access is no longer granted")
        }
        if (item.requiresShizuku && !capabilities.shizukuAuthorized()) {
            return skipped(item, "Shizuku is not available right now")
        }

        var known: Set<String> = emptySet()
        if (item.category == CleaningCategory.CORPSE_LEFTOVER || item.category == CleaningCategory.APP_CACHE) {
            val pkg = item.owningPackage ?: return skipped(item, "No package recorded for this folder")
            val snapshot = inventory.knownPackageNames()
            if (snapshot == null || snapshot.size < MIN_PLAUSIBLE_PACKAGES) {
                return skipped(item, "Could not verify which apps are installed")
            }
            known = snapshot
            // Two different questions: "known" includes data-kept uninstalls, "installed" does not. A failed query is
            // UNKNOWN and is never read as "not installed" (that would be the unsafe answer for a leftover).
            val state = inventory.installState(pkg)
            if (state == InstallState.UNKNOWN) return skipped(item, "Could not verify whether $pkg is installed")
            if (item.category == CleaningCategory.CORPSE_LEFTOVER) {
                val knownLower = known.any { it.equals(pkg, ignoreCase = true) }
                if (state != InstallState.NOT_KNOWN || pkg in known || knownLower) {
                    return skipped(item, "$pkg is known to Android again; its folder is no longer a leftover")
                }
            } else if (state != InstallState.INSTALLED || pkg !in known) {
                return skipped(item, "$pkg is not currently installed")
            }
        }

        return if (item.requiresShizuku) processPrivileged(item, known) else processStandard(item)
    }

    // ---------------------------------------------------------------- privileged path

    private suspend fun processPrivileged(item: ScanItem, known: Set<String>): ItemOutcome {
        val expected = item.fingerprint ?: return skipped(item, "No scan snapshot for this item; scan again")
        val request = item.toMaintenanceRequest(known)
            ?: return skipped(item, "This item is not a kind of cleanup the Shizuku service supports, so it was left alone")

        val readiness = capabilities.readiness(request.operation)
        if (!readiness.canAttempt) return skipped(item, "Not available right now: ${readiness.reason}")

        when (val now = privileged.stat(item.path)) {
            is FsResult.Ok -> if (!now.value.sameObjectAs(expected)) {
                return skipped(item, "Changed since the scan; scan again to include it")
            }
            FsResult.NotFound -> return skipped(item, "Already gone")
            is FsResult.Unavailable -> return failed(item, "The Shizuku service could not be reached: ${now.why}")
            is FsResult.Denied -> return failed(item, "Could not examine this item: ${now.why}")
            is FsResult.Failed -> return failed(item, "Could not examine this item: ${now.why}")
        }

        val result = try {
            privileged.execute(request)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return failed(item, t.message ?: "Cleanup failed unexpectedly")
        }
        return outcomeFrom(item, result)
    }

    // ---------------------------------------------------------------- standard (app process) path

    private suspend fun processStandard(item: ScanItem): ItemOutcome {
        if (item.category == CleaningCategory.DUPLICATE) {
            if (item.keepPath == item.path) return skipped(item, "The copy that should stay is this very file")
            val keepThen = item.keepFingerprint
            val keepNow = item.keepPath?.let { (standardBackend.lstat(it) as? FsResult.Ok)?.value }
            if (keepNow == null || keepThen == null || !keepNow.sameObjectAs(keepThen)) {
                return skipped(item, "The copy that should stay changed or disappeared")
            }
        }

        val expected = item.fingerprint ?: return skipped(item, "No scan snapshot for this item; scan again")
        when (val now = standardBackend.lstat(item.path)) {
            is FsResult.Ok -> if (!now.value.sameObjectAs(expected)) {
                return skipped(item, "Changed since the scan; scan again to include it")
            }
            FsResult.NotFound -> return skipped(item, "Already gone")
            is FsResult.Unavailable -> return failed(item, "Could not examine this item: ${now.why}")
            is FsResult.Denied -> return failed(item, "Could not examine this item: ${now.why}")
            is FsResult.Failed -> return failed(item, "Could not examine this item: ${now.why}")
        }

        // ---- DELETE ----
        val result = try {
            standardBackend.delete(item.path, item.deleteMode, expected)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return failed(item, t.message ?: "Delete failed unexpectedly")
        }

        // ---- VERIFY ----
        val post = observePostState(item.path, item.deleteMode, { standardBackend.lstat(it) }, { standardBackend.list(it) })
        return outcomeFrom(item, classifyDeletion(item.deleteMode, result, post))
    }

    // ---------------------------------------------------------------- reporting

    /** The only place a [MaintenanceResult] becomes a user-visible status. */
    private fun outcomeFrom(item: ScanItem, r: MaintenanceResult): ItemOutcome = when (r) {
        is MaintenanceResult.Success -> ItemOutcome(item, ItemStatus.DELETED, r.message, r.bytesFreed)
        is MaintenanceResult.Partial -> ItemOutcome(item, ItemStatus.PARTIAL, r.message, r.bytesFreed)
        is MaintenanceResult.Unverified -> ItemOutcome(item, ItemStatus.UNVERIFIED, r.message, r.bytesFreed)
        is MaintenanceResult.Unsupported -> skipped(item, "Not available right now: ${r.message}")
        is MaintenanceResult.Failure -> when (r.kind) {
            FailureKind.PRECONDITION_CHANGED, FailureKind.REFUSED -> skipped(item, r.message)
            FailureKind.UNAVAILABLE, FailureKind.LIMIT, FailureKind.CANCELLED, FailureKind.IO_ERROR ->
                ItemOutcome(item, ItemStatus.FAILED, r.message, r.bytesFreed)
        }
    }

    private fun skipped(item: ScanItem, why: String) = ItemOutcome(item, ItemStatus.SKIPPED, why)
    private fun failed(item: ScanItem, why: String) = ItemOutcome(item, ItemStatus.FAILED, why)
}
