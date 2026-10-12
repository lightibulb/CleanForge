package com.cleanforge.core.privileged

import com.cleanforge.core.fs.PathRules
import com.cleanforge.core.shizuku.ShizukuState
import com.cleanforge.core.shizuku.ShizukuStateSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Tracks, per operation, whether it is supported, what it needs, whether the user has authorized it, whether it is
 * available right now, and what its limits are. A connected binder proves none of that for a particular operation,
 * so the registry only says VERIFIED for something a probe or a real result demonstrated.
 *
 * [probe] is READ-ONLY: it binds the service, asks for uid/apiVersion and lists Android/data and Android/obb. It never
 * deletes anything to test deletion, so deletions top out at PREREQUISITES_MET until one succeeds.
 */
class CapabilityRegistry(
    private val shizuku: ShizukuStateSource,
    private val provider: PrivilegedSessionProvider,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** A probe younger than this is reused unless forced or the Shizuku state changed. */
    private val minProbeIntervalMs: Long = 3_000L,
    /** Bound for the blocking calls a probe makes, so a hung service cannot hang a scan. */
    private val probeCallTimeoutMs: Long = 15_000L,
    /** Worker scope for the blocking probe calls. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** A probe makes binder calls, so it never runs on the caller's (possibly main) thread. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val probeMutex = Mutex()
    private val _report = MutableStateFlow(CapabilityReport.unknown(shizuku.state.value))
    val report: StateFlow<CapabilityReport> = _report.asStateFlow()

    /**
     * Cheap and I/O-free; safe to call from anywhere. Combines the LIVE Shizuku state (so a stale VERIFIED can never
     * outlive a stopped Shizuku) with the last probe or operation result.
     */
    fun current(op: PrivilegedOperation): OperationCapability {
        val derived = describeState(shizuku.state.value)
        if (derived != null) {
            return OperationCapability(op, derived.first, derived.second, Limitations.of(op), null)
        }
        return _report.value.operations[op]
            ?: OperationCapability(op, CapabilityStatus.UNKNOWN, "Not checked yet", Limitations.of(op))
    }

    /** Records that a real operation worked. Upgrades the operation to VERIFIED. */
    fun markVerified(op: PrivilegedOperation) {
        val now = clock()
        _report.update { r ->
            val old = r.of(op)
            r.copy(operations = r.operations + (op to old.copy(
                status = CapabilityStatus.VERIFIED,
                reason = "Worked at the last attempt",
                limitations = Limitations.of(op),
                checkedAtMs = now
            )))
        }
    }

    /** Records that a transport failure just made [op] unusable. A later probe can restore it. */
    fun markUnavailable(op: PrivilegedOperation, reason: String) {
        val now = clock()
        _report.update { r ->
            val old = r.of(op)
            r.copy(operations = r.operations + (op to old.copy(
                status = CapabilityStatus.UNAVAILABLE,
                reason = reason,
                limitations = Limitations.of(op),
                checkedAtMs = now
            )))
        }
    }

    /** Fresh, read-only probe of everything. Never throws. */
    suspend fun probe(force: Boolean = false): CapabilityReport = withContext(dispatcher) { probeLocked(force) }

    private suspend fun probeLocked(force: Boolean): CapabilityReport = probeMutex.withLock {
        val last = _report.value
        val lastAt = last.probedAtMs
        if (!force && lastAt != null && clock() - lastAt < minProbeIntervalMs && last.shizuku == shizuku.state.value) {
            return@withLock last
        }
        shizuku.refresh()
        val state = shizuku.state.value
        val report = try {
            probeNow(state)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            allOperations(state, null, null, CapabilityStatus.UNAVAILABLE, "The capability check failed: ${e.message ?: e.javaClass.simpleName}")
        }
        val merged = keepDemonstrated(last, report)
        _report.value = merged
        merged
    }

    /**
     * A read-only probe can only say "ready" for a deletion. If a real deletion already succeeded and nothing since has
     * made the operation unavailable (that would have replaced VERIFIED), the probe must not downgrade it.
     */
    private fun keepDemonstrated(old: CapabilityReport, fresh: CapabilityReport): CapabilityReport = fresh.copy(
        operations = fresh.operations.mapValues { (op, cap) ->
            val before = old.operations[op]
            if (cap.status == CapabilityStatus.PREREQUISITES_MET &&
                before != null && before.status == CapabilityStatus.VERIFIED && before.checkedAtMs != null
            ) before else cap
        }
    )

    private suspend fun probeNow(state: ShizukuState): CapabilityReport {
        val derived = describeState(state)
        if (derived != null) {
            return allOperations(state, null, null, derived.first, derived.second)
        }
        return when (val session = provider.acquire()) {
            is SessionResult.NotReady ->
                allOperations(state, null, null, session.reason.toCapabilityStatus(), session.message)
            is SessionResult.Ready -> probeReady(state, session.api)
        }
    }

    private suspend fun probeReady(state: ShizukuState, api: PrivilegedFileApi): CapabilityReport {
        val now = clock()
        val dataPath = "${PathRules.ROOT}/Android/data"
        val obbPath = "${PathRules.ROOT}/Android/obb"
        val probe = try {
            scope.runWithDeadline(probeCallTimeoutMs) {
                Probed(api.uid(), api.apiVersion(), api.list(dataPath), api.list(obbPath))
            }
        } catch (e: TransportException) {
            provider.invalidate()
            return allOperations(state, null, null, CapabilityStatus.UNAVAILABLE,
                "The Shizuku service stopped while it was being checked. Try again.")
        } catch (e: DeadlineExceeded) {
            provider.restart()
            return allOperations(state, null, null, CapabilityStatus.UNAVAILABLE,
                "The Shizuku service did not answer in time. Try again.")
        }

        val dataOk = probe.data.status == ReplyStatus.OK
        val obbOk = probe.obb.status == ReplyStatus.OK
        val unreadable = !dataOk && !obbOk
        val readReason = when {
            unreadable -> "The Shizuku shell cannot read Android/data on this device (${describe(probe.data.status)}). " +
                "This depends on the Android version and manufacturer."
            dataOk && obbOk -> "Android/data and Android/obb are readable through Shizuku."
            dataOk -> "Android/data is readable; Android/obb is not (${describe(probe.obb.status)})."
            else -> "Android/obb is readable; Android/data is not (${describe(probe.data.status)})."
        }
        val ops = LinkedHashMap<PrivilegedOperation, OperationCapability>()
        fun put(op: PrivilegedOperation, status: CapabilityStatus, reason: String) {
            ops[op] = OperationCapability(op, status, reason, Limitations.of(op), now)
        }
        if (unreadable) {
            for (op in PrivilegedOperation.entries) put(op, CapabilityStatus.UNSUPPORTED, readReason)
        } else {
            put(PrivilegedOperation.LIST_EXTERNAL_APP_DATA, CapabilityStatus.VERIFIED, readReason)
            put(PrivilegedOperation.MEASURE_EXTERNAL_APP_DIR, CapabilityStatus.PREREQUISITES_MET,
                "Listing works; measuring has not been demonstrated yet.")
            if (dataOk) {
                put(PrivilegedOperation.CLEAR_EXTERNAL_APP_CACHE, CapabilityStatus.PREREQUISITES_MET,
                    "Everything that can be checked without deleting is in place; clearing has not been demonstrated yet.")
            } else {
                put(PrivilegedOperation.CLEAR_EXTERNAL_APP_CACHE, CapabilityStatus.UNSUPPORTED,
                    "App caches live in Android/data, which the Shizuku shell cannot read here (${describe(probe.data.status)}).")
            }
            put(PrivilegedOperation.REMOVE_UNINSTALLED_APP_DATA, CapabilityStatus.PREREQUISITES_MET,
                "Everything that can be checked without deleting is in place; removal has not been demonstrated yet.")
        }
        return CapabilityReport(state, probe.uid, probe.apiVersion, ops, now)
    }

    private fun allOperations(
        state: ShizukuState,
        uid: Int?,
        apiVersion: Int?,
        status: CapabilityStatus,
        reason: String
    ): CapabilityReport {
        val now = clock()
        return CapabilityReport(
            shizuku = state,
            serviceUid = uid,
            serviceApiVersion = apiVersion,
            operations = PrivilegedOperation.entries.associateWith {
                OperationCapability(it, status, reason, Limitations.of(it), now)
            },
            probedAtMs = now
        )
    }

    private class Probed(val uid: Int, val apiVersion: Int, val data: ListReply, val obb: ListReply)

    private fun describe(status: ReplyStatus): String = when (status) {
        ReplyStatus.OK -> "readable"
        ReplyStatus.NOT_FOUND -> "folder not found"
        ReplyStatus.REFUSED -> "blocked by CleanForge's own rules"
        ReplyStatus.OS_DENIED -> "the system refused access"
        ReplyStatus.FAILED -> "the check failed"
    }

    companion object {
        /** Actionable text for a Shizuku state that blocks everything, or null if Shizuku is authorized. */
        fun describeState(state: ShizukuState): Pair<CapabilityStatus, String>? = when (state) {
            is ShizukuState.Authorized -> null
            ShizukuState.NotInstalled -> CapabilityStatus.UNAVAILABLE to
                "Shizuku is not installed. Install it from its official page, start it, then come back."
            ShizukuState.NotRunning -> CapabilityStatus.UNAVAILABLE to
                "Shizuku is installed but not running. Start it (Wireless debugging or adb), then come back."
            ShizukuState.Disconnected -> CapabilityStatus.UNAVAILABLE to
                "The Shizuku service stopped. Start it again, then come back."
            ShizukuState.ServerTooOld -> CapabilityStatus.UNSUPPORTED to
                "The running Shizuku is too old (version 11 or newer is required). Update the Shizuku app."
            ShizukuState.PermissionDenied -> CapabilityStatus.NEEDS_AUTHORIZATION to
                "Allow CleanForge in the Shizuku permission dialog."
            ShizukuState.PermissionPermanentlyDenied -> CapabilityStatus.NEEDS_AUTHORIZATION to
                "Permission was denied with \"don't ask again\". Open the Shizuku app and allow CleanForge there."
            is ShizukuState.Error -> CapabilityStatus.UNAVAILABLE to "Shizuku reported a problem: ${state.message}"
        }
    }
}
