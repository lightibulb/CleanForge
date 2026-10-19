package com.cleanforge.core.privileged

import com.cleanforge.core.backend.DirListing
import com.cleanforge.core.backend.FsResult
import com.cleanforge.core.backend.MIN_PLAUSIBLE_PACKAGES
import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.fs.DeleteResult
import com.cleanforge.core.fs.DeleteStatus
import com.cleanforge.core.fs.FsStat
import com.cleanforge.core.fs.SizeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * The single place privileged work is requested. Everything that reaches the Shizuku service goes through here, so
 * validation, capability checks, timeouts, cancellation, concurrency limits and result classification live in one spot.
 *
 * Reads return [FsResult] (never "null means everything"). Maintenance returns a [MaintenanceResult]: success,
 * partial, failure, unsupported or unverified. A weaker operation is never substituted when one is not possible.
 */
interface PrivilegedExecutor {
    suspend fun stat(path: String): FsResult<FsStat>
    suspend fun list(path: String): FsResult<DirListing>
    suspend fun measure(path: String): FsResult<SizeResult>
    suspend fun execute(request: MaintenanceRequest): MaintenanceResult

    /** Running app processes and their memory. Needs Shizuku; every other outcome says why there is no list. */
    suspend fun processMemory(): FsResult<List<ProcessMemoryEntry>> =
        FsResult.Unavailable("Not supported by this executor")
}

data class ExecutionPolicy(
    val statTimeoutMs: Long = 10_000L,
    val listTimeoutMs: Long = 20_000L,
    val sizeTimeoutMs: Long = 60_000L,
    val deleteTimeoutMs: Long = 300_000L,
    /** Binder calls running at the same time. Deletions are always one at a time. */
    val maxConcurrentReads: Int = 2,
    /** Extra attempts for a READ after the service died. Deletions are never retried. */
    val readRetries: Int = 1
)

class DefaultPrivilegedExecutor(
    private val provider: PrivilegedSessionProvider,
    private val registry: CapabilityRegistry? = null,
    private val policy: ExecutionPolicy = ExecutionPolicy(),
    /** Worker scope for blocking binder calls. Outlives any single caller; see [runWithDeadline]. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : PrivilegedExecutor {

    private val readPermits = Semaphore(policy.maxConcurrentReads.coerceAtLeast(1))
    private val writeLock = Mutex()

    /** The api instance and package set last pushed as the installed-apps guard. A new binding must push again. */
    @Volatile private var pushedGuard: Pair<PrivilegedFileApi, Set<String>>? = null

    // ------------------------------------------------------------------ reads

    override suspend fun stat(path: String): FsResult<FsStat> =
        read<FsStat>(PrivilegedOperation.LIST_EXTERNAL_APP_DATA, policy.statTimeoutMs) { api ->
            val r = api.stat(path)
            when (r.status) {
                ReplyStatus.OK -> r.stat?.let { FsResult.Ok(it) } ?: FsResult.Failed("Malformed reply from the Shizuku service")
                ReplyStatus.NOT_FOUND -> FsResult.NotFound
                ReplyStatus.REFUSED -> FsResult.Denied("Blocked by CleanForge's path rules")
                ReplyStatus.OS_DENIED -> FsResult.Denied("The system refused access for the Shizuku shell")
                ReplyStatus.FAILED -> FsResult.Failed("The Shizuku service could not examine this path")
            }
        }

    override suspend fun processMemory(): FsResult<List<ProcessMemoryEntry>> =
        read<List<ProcessMemoryEntry>>(PrivilegedOperation.LIST_EXTERNAL_APP_DATA, policy.listTimeoutMs) { api ->
            val r = api.processMemory()
            when (r.status) {
                ReplyStatus.OK -> FsResult.Ok(r.entries)
                ReplyStatus.NOT_FOUND -> FsResult.NotFound
                ReplyStatus.REFUSED -> FsResult.Denied("The Shizuku service is too old for this: reopen CleanForge after updating")
                ReplyStatus.OS_DENIED -> FsResult.Denied("Android refused to show other apps' memory use")
                ReplyStatus.FAILED -> FsResult.Failed("The service could not read process memory")
            }
        }

    override suspend fun list(path: String): FsResult<DirListing> {
        val result = read<DirListing>(PrivilegedOperation.LIST_EXTERNAL_APP_DATA, policy.listTimeoutMs) { api ->
            val r = api.list(path)
            when (r.status) {
                ReplyStatus.OK -> FsResult.Ok(DirListing(r.names, r.truncated))
                ReplyStatus.NOT_FOUND -> FsResult.NotFound
                ReplyStatus.REFUSED -> FsResult.Denied("Blocked by CleanForge's path rules")
                ReplyStatus.OS_DENIED -> FsResult.Denied("The system refused to list this folder for the Shizuku shell")
                ReplyStatus.FAILED -> FsResult.Failed("The Shizuku service could not list this folder")
            }
        }
        if (result is FsResult.Ok) noteVerified(PrivilegedOperation.LIST_EXTERNAL_APP_DATA)
        return result
    }

    override suspend fun measure(path: String): FsResult<SizeResult> {
        val result = read<SizeResult>(PrivilegedOperation.MEASURE_EXTERNAL_APP_DIR, policy.sizeTimeoutMs) { api ->
            val r = api.size(path)
            when (r.status) {
                ReplyStatus.OK -> r.size?.let { FsResult.Ok(it) } ?: FsResult.Failed("Malformed reply from the Shizuku service")
                ReplyStatus.NOT_FOUND -> FsResult.NotFound
                ReplyStatus.REFUSED -> FsResult.Denied("Blocked by CleanForge's path rules")
                ReplyStatus.OS_DENIED -> FsResult.Denied("The system refused access for the Shizuku shell")
                ReplyStatus.FAILED -> FsResult.Failed("The Shizuku service could not measure this folder")
            }
        }
        if (result is FsResult.Ok) noteVerified(PrivilegedOperation.MEASURE_EXTERNAL_APP_DIR)
        return result
    }

    private suspend fun <T> read(
        op: PrivilegedOperation,
        timeoutMs: Long,
        block: (PrivilegedFileApi) -> FsResult<T>
    ): FsResult<T> = readPermits.withPermit {
        var last: FsResult<T> = FsResult.Unavailable("The Shizuku service is not reachable")
        for (round in 0..policy.readRetries.coerceAtLeast(0)) {
            when (val a = attempt(timeoutMs, null, block)) {
                is Attempt.Done -> return@withPermit a.value
                is Attempt.NotReady -> return@withPermit FsResult.Unavailable(a.message)
                // The binding was dropped inside attempt(); the next round binds a fresh service.
                is Attempt.Died -> {
                    last = FsResult.Unavailable(a.message)
                    registry?.markUnavailable(op, a.message)
                }
                is Attempt.TimedOut -> return@withPermit FsResult.Unavailable(a.message)
                is Attempt.CallFailed -> return@withPermit FsResult.Failed(a.message)
            }
        }
        last
    }

    // ------------------------------------------------------------------ maintenance

    override suspend fun execute(request: MaintenanceRequest): MaintenanceResult {
        val target = request.targetPathOrNull()
            ?: return MaintenanceResult.Failure(
                FailureKind.REFUSED,
                "Not a valid package identifier: ${request.packageName.take(80)}"
            )
        if (request is MaintenanceRequest.RemoveUninstalledAppData && request.knownPackages.size < MIN_PLAUSIBLE_PACKAGES) {
            return MaintenanceResult.Failure(
                FailureKind.REFUSED,
                "The list of known apps is too short to trust, so no app data will be deleted"
            )
        }
        gate(request.operation)?.let { return it }
        return writeLock.withLock { runDelete(request, target, request.deleteMode()) }
    }

    /** Null if the operation may be attempted, otherwise the not-attempted result with an actionable message. */
    private suspend fun gate(op: PrivilegedOperation): MaintenanceResult.Unsupported? {
        val reg = registry ?: return null
        var cap = reg.current(op)
        if (!cap.canAttempt && (cap.status == CapabilityStatus.UNKNOWN || cap.status == CapabilityStatus.UNAVAILABLE)) {
            cap = reg.probe().of(op)
        }
        return if (cap.canAttempt) null else MaintenanceResult.Unsupported(cap.status, cap.reason)
    }

    private suspend fun runDelete(request: MaintenanceRequest, target: String, mode: DeleteMode): MaintenanceResult {
        val guard: Set<String>? = (request as? MaintenanceRequest.RemoveUninstalledAppData)?.knownPackages
        val a = attempt(policy.deleteTimeoutMs, { api -> api.cancelCurrent() }) { api ->
            if (guard != null) {
                val pushed = pushedGuard
                if (pushed == null || pushed.first !== api || pushed.second != guard) {
                    if (api.setProtectedPackages(guard) < 0) {
                        return@attempt DeleteResult(
                            DeleteStatus.REFUSED,
                            detail = "The Shizuku service rejected the installed-apps guard"
                        )
                    }
                    pushedGuard = api to guard
                }
            }
            api.delete(target, mode, request.expected)
        }
        return when (a) {
            is Attempt.Done -> afterDelete(request, target, mode, a.value)
            is Attempt.NotReady -> MaintenanceResult.Unsupported(a.reason.toCapabilityStatus(), a.message)
            is Attempt.Died -> verifyUncertain(target, mode, "The Shizuku service stopped during the deletion")
            is Attempt.TimedOut -> verifyUncertain(target, mode, a.message)
            is Attempt.CallFailed -> verifyUncertain(target, mode, "The deletion call failed (${a.message})")
        }
    }

    private suspend fun afterDelete(
        request: MaintenanceRequest,
        target: String,
        mode: DeleteMode,
        result: DeleteResult
    ): MaintenanceResult {
        // Only a reported success needs its end state checked; every other status already says what went wrong.
        val post = if (result.status == DeleteStatus.OK) observe(target, mode) else PostState.Unknown("not checked")
        val outcome = classifyDeletion(mode, result, post)
        if (outcome is MaintenanceResult.Success) registry?.markVerified(request.operation)
        return outcome
    }

    /** The call ended without a trustworthy answer. Look at the target through a fresh binding and say what is seen. */
    private suspend fun verifyUncertain(target: String, mode: DeleteMode, why: String): MaintenanceResult =
        when (val post = observe(target, mode)) {
            PostState.Gone ->
                MaintenanceResult.Success(0, 0L, "$why. The target is gone now; how much space it freed is unknown")
            PostState.ExistsEmpty ->
                MaintenanceResult.Success(0, 0L, "$why. The folder is empty now; how much space it freed is unknown")
            is PostState.ExistsNonEmpty ->
                MaintenanceResult.Unverified("$why. ${post.detail}; the cleanup may have stopped partway. Scan again")
            is PostState.Unknown ->
                MaintenanceResult.Unverified("$why, and the result could not be confirmed afterwards (${post.why}). Scan again to see the current state")
        }

    private suspend fun observe(target: String, mode: DeleteMode): PostState =
        observePostState(target, mode, { stat(it) }, { list(it) })

    private fun describeDuration(ms: Long): String = if (ms < 1_000L) "$ms ms" else "${ms / 1_000L} s"

    private fun noteVerified(op: PrivilegedOperation) {
        val reg = registry ?: return
        if (reg.current(op).status != CapabilityStatus.VERIFIED) reg.markVerified(op)
    }

    // ------------------------------------------------------------------ one bounded call

    private sealed interface Attempt<out T> {
        data class Done<T>(val value: T) : Attempt<T>
        data class NotReady(val reason: UnavailableReason, val message: String) : Attempt<Nothing>
        data class Died(val message: String) : Attempt<Nothing>
        data class TimedOut(val message: String) : Attempt<Nothing>
        data class CallFailed(val message: String) : Attempt<Nothing>
    }

    /**
     * Acquires a service and runs ONE blocking call with a deadline. [onCancel] runs (best effort, in the
     * background) when the caller is cancelled while the call is in flight; it is how a running delete is told to stop.
     * A deadline restarts the service process, which also releases the thread stuck inside the blocked call.
     */
    private suspend fun <T> attempt(
        timeoutMs: Long,
        onCancel: ((PrivilegedFileApi) -> Unit)?,
        block: (PrivilegedFileApi) -> T
    ): Attempt<T> {
        val api = when (val s = provider.acquire()) {
            is SessionResult.Ready -> s.api
            is SessionResult.NotReady -> return Attempt.NotReady(s.reason, s.message)
        }
        try {
            return Attempt.Done(scope.runWithDeadline(timeoutMs) { block(api) })
        } catch (e: DeadlineExceeded) {
            provider.restart()
            return Attempt.TimedOut("The Shizuku service did not answer within ${describeDuration(timeoutMs)} and was restarted")
        } catch (e: CancellationException) {
            if (onCancel != null) {
                scope.launch {
                    try {
                        onCancel(api)
                    } catch (ignored: Exception) {
                        // best effort
                    }
                }
            }
            throw e
        } catch (e: TransportException) {
            return when (e.kind) {
                TransportException.Kind.SERVICE_DIED -> {
                    provider.invalidate()
                    Attempt.Died(e.message ?: "The Shizuku service stopped")
                }
                TransportException.Kind.CALL_FAILED -> Attempt.CallFailed(e.message ?: "The call failed")
            }
        } catch (e: Exception) {
            return Attempt.CallFailed(e.message ?: e.javaClass.simpleName)
        }
    }
}
