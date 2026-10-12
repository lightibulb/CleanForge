package com.cleanforge.core.privileged

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout

/** A blocking call did not finish before its deadline. The call itself may still be running somewhere. */
class DeadlineExceeded(val timeoutMs: Long) : Exception("No answer within ${timeoutMs} ms")

/**
 * Runs the BLOCKING [block] on [this] scope's dispatcher and waits for it with a deadline.
 *
 * Blocking binder calls cannot be interrupted, and `withContext` would not return until they do. So the work runs in a
 * separate `async` on a long-lived scope and only the waiting is cancellable: when the deadline passes, or the caller
 * is cancelled, the caller is released immediately. The worker thread stays occupied until the call returns on its own
 * or the service process is stopped, which is what the callers do after a [DeadlineExceeded].
 *
 * Exceptions thrown by [block] (for example [TransportException]) are rethrown unchanged.
 */
suspend fun <T> CoroutineScope.runWithDeadline(timeoutMs: Long, block: () -> T): T {
    val job = async { block() }
    try {
        return withTimeout(timeoutMs) { job.await() }
    } catch (e: TimeoutCancellationException) {
        job.cancel()
        throw DeadlineExceeded(timeoutMs)
    } catch (e: CancellationException) {
        job.cancel()
        throw e
    }
}
