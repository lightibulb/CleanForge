package com.cleanforge.core.privileged

enum class UnavailableReason {
    NOT_INSTALLED,
    NOT_RUNNING,
    NEEDS_PERMISSION,
    PERMISSION_PERMANENTLY_DENIED,
    SERVER_TOO_OLD,
    SHIZUKU_ERROR,
    BIND_FAILED,
    BIND_TIMEOUT,
    SERVICE_DIED,
    SERVICE_VERSION_MISMATCH
}

sealed interface SessionResult {
    data class Ready(val api: PrivilegedFileApi) : SessionResult

    /** [message] is plain language and actionable; it is shown to the user. */
    data class NotReady(val reason: UnavailableReason, val message: String) : SessionResult
}

/** Owns the binding to the privileged service. The Shizuku implementation is [com.cleanforge.core.shizuku.ShizukuFileServiceConnector]. */
interface PrivilegedSessionProvider {
    /** A live, version-checked service, or the reason there is none. Never throws; never blocks the caller's thread. */
    suspend fun acquire(): SessionResult

    /** Forget the current binding after a transport failure; the next [acquire] binds again. */
    fun invalidate()

    /** Stop the service process (releases calls blocked inside it) and forget the binding. */
    suspend fun restart()
}

/** How an unavailable reason maps onto the capability vocabulary. */
fun UnavailableReason.toCapabilityStatus(): CapabilityStatus = when (this) {
    UnavailableReason.NEEDS_PERMISSION,
    UnavailableReason.PERMISSION_PERMANENTLY_DENIED -> CapabilityStatus.NEEDS_AUTHORIZATION
    UnavailableReason.SERVER_TOO_OLD,
    UnavailableReason.SERVICE_VERSION_MISMATCH -> CapabilityStatus.UNSUPPORTED
    UnavailableReason.NOT_INSTALLED,
    UnavailableReason.NOT_RUNNING,
    UnavailableReason.SHIZUKU_ERROR,
    UnavailableReason.BIND_FAILED,
    UnavailableReason.BIND_TIMEOUT,
    UnavailableReason.SERVICE_DIED -> CapabilityStatus.UNAVAILABLE
}
