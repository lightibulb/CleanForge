package com.cleanforge.core.privileged

import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.fs.DeleteResult
import com.cleanforge.core.fs.FsStat
import com.cleanforge.core.fs.SizeResult

/** Version of the app <-> service protocol. Bump when a reply layout changes. The service reports it via apiVersion(). */
object PrivilegedProtocol {
    const val API_VERSION = 1
    const val MIN_API_VERSION = 1
}

/**
 * Why a service call answered the way it did. These travel over the wire (see [WireCodec]); append only.
 */
enum class ReplyStatus(val code: Int) {
    OK(0),

    /** The path was examined and is not there. */
    NOT_FOUND(1),

    /** CleanForge's own path policy or installed-app guard refused the request. */
    REFUSED(2),

    /** The operating system refused (permission denied, listing failed on an existing folder). */
    OS_DENIED(3),

    /** Anything else, including a malformed reply. */
    FAILED(4);

    companion object {
        fun fromCode(code: Long): ReplyStatus = entries.firstOrNull { it.code.toLong() == code } ?: FAILED
    }
}

data class StatReply(val status: ReplyStatus, val stat: FsStat? = null)

data class ListReply(val status: ReplyStatus, val names: List<String> = emptyList(), val truncated: Boolean = false)

data class SizeReply(val status: ReplyStatus, val size: SizeResult? = null)
data class ProcessListReply(val status: ReplyStatus, val entries: List<ProcessMemoryEntry> = emptyList())

/**
 * The transport broke: the service died, the call could not be delivered, or the reply was unusable.
 * Anything thrown by a [PrivilegedFileApi] must be this type so that callers never see Android classes.
 */
class TransportException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind {
        /** The service process is gone (DeadObjectException and friends). The binding must be rebuilt. */
        SERVICE_DIED,

        /** The call itself failed (too large, rejected, runtime error) but the service may be fine. */
        CALL_FAILED
    }
}

/**
 * Pure-Kotlin view of the Shizuku user service. The production implementation wraps the AIDL stub; tests use
 * [LocalPrivilegedFileApi] (the real service logic over a fake filesystem) or a hand-written fake.
 *
 * Calls are BLOCKING. They must be made off the main thread, and only through [PrivilegedExecutor], which adds
 * timeouts, cancellation and bounded concurrency.
 */
interface PrivilegedFileApi {
    @Throws(TransportException::class) fun apiVersion(): Int
    @Throws(TransportException::class) fun uid(): Int
    @Throws(TransportException::class) fun stat(path: String): StatReply
    @Throws(TransportException::class) fun list(path: String): ListReply
    @Throws(TransportException::class) fun size(path: String): SizeReply
    @Throws(TransportException::class) fun delete(path: String, mode: DeleteMode, expected: FsStat?): DeleteResult

    /** Asks the service to stop the delete in progress before it touches its next entry. Best effort. */
    @Throws(TransportException::class) fun cancelCurrent()

    /**
     * Replaces the service's installed-apps guard: tree deletions of any listed package (case-insensitive) are
     * refused. Returns how many names the service accepted, or -1 if it rejected the whole list (too long).
     * Protects against a logic bug in the app, not against a hostile app.
     */
    @Throws(TransportException::class) fun setProtectedPackages(packages: Collection<String>): Int
    /** Running app processes and their memory (read-only). Older services without this call answer with an error. */
    @Throws(TransportException::class) fun processMemory(): ProcessListReply = ProcessListReply(ReplyStatus.REFUSED)
}
