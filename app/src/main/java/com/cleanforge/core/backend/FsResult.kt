package com.cleanforge.core.backend

/**
 * Outcome of a READ at the backend boundary. Replaces the old "null means everything" convention, where
 * `null` could mean missing, refused, empty, or "the Shizuku service is dead", and callers could not tell which.
 *
 * Callers must decide per case; none of the failure cases may ever be treated as "nothing found" or "already gone".
 */
sealed interface FsResult<out T> {
    /** The operation ran and produced [value]. */
    data class Ok<out T>(val value: T) : FsResult<T>

    /** The path was looked up successfully and does not exist. This is an answer, not a failure. */
    data object NotFound : FsResult<Nothing>

    /** The path policy or the OS refused the request. The data may exist; we were not allowed to see it. */
    data class Denied(val why: String) : FsResult<Nothing>

    /** The channel itself was not usable: no Shizuku, no permission, bind timeout, service died, call timed out. */
    data class Unavailable(val why: String) : FsResult<Nothing>

    /** The operation ran but could not complete (I/O error, unexpected reply). */
    data class Failed(val why: String) : FsResult<Nothing>
}

/** The value if the call succeeded, otherwise null. Use ONLY where every failure case may safely be ignored. */
fun <T> FsResult<T>.valueOrNull(): T? = (this as? FsResult.Ok<T>)?.value

/** True for every outcome in which we do NOT know whether the path exists (anything except Ok and NotFound). */
val FsResult<*>.isInconclusive: Boolean
    get() = this is FsResult.Denied || this is FsResult.Unavailable || this is FsResult.Failed

/** Human-readable reason for an inconclusive result, or null for Ok/NotFound. */
fun FsResult<*>.reasonOrNull(): String? = when (this) {
    is FsResult.Denied -> why
    is FsResult.Unavailable -> why
    is FsResult.Failed -> why
    else -> null
}
