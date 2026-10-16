package com.cleanforge.core.privileged

import com.cleanforge.core.backend.DirListing
import com.cleanforge.core.backend.FsResult
import com.cleanforge.core.fs.DeleteMode
import com.cleanforge.core.fs.DeleteResult
import com.cleanforge.core.fs.DeleteStatus
import com.cleanforge.core.fs.FsStat
import com.cleanforge.core.fs.PathException
import com.cleanforge.core.fs.PathRules
import com.cleanforge.core.model.CleaningCategory
import com.cleanforge.core.model.ScanItem

enum class ExternalArea(val folder: String) { DATA("data"), OBB("obb") }

/**
 * A privileged maintenance action, described by WHAT it is and for WHICH package, never by a free-form path.
 * The executor builds the target path itself from a validated package name, so no caller can aim a privileged
 * delete at an arbitrary location.
 */
sealed interface MaintenanceRequest {
    val operation: PrivilegedOperation
    val packageName: String
    /** What the scan saw. The service refuses to act if the object's identity (device, inode) changed. */
    val expected: FsStat

    /** Contents of Android/data/<package>/cache of an installed app. The folder itself stays. */
    data class ClearExternalAppCache(
        override val packageName: String,
        override val expected: FsStat
    ) : MaintenanceRequest {
        override val operation get() = PrivilegedOperation.CLEAR_EXTERNAL_APP_CACHE
    }

    /**
     * Android/<area>/<package> of an app Android no longer knows. [knownPackages] is everything Android still has an
     * identity for (installed AND uninstalled-with-data-kept); the service refuses to delete any of those.
     */
    data class RemoveUninstalledAppData(
        val area: ExternalArea,
        override val packageName: String,
        override val expected: FsStat,
        val knownPackages: Set<String>
    ) : MaintenanceRequest {
        override val operation get() = PrivilegedOperation.REMOVE_UNINSTALLED_APP_DATA
    }
}

/** 
 * The path a request acts on, or null if the package identifier is not a valid package name 
 * OR if the constructed path violates structural rules.
 */
fun MaintenanceRequest.targetPathOrNull(): String? {
    // 1. Validate Package Name Syntax
    if (!PathRules.isValidPackageName(packageName)) return null
    
    // 2. Construct Path
    val rawPath = when (this) {
        is MaintenanceRequest.ClearExternalAppCache -> "${PathRules.ROOT}/Android/data/$packageName/cache"
        is MaintenanceRequest.RemoveUninstalledAppData -> "${PathRules.ROOT}/Android/${area.folder}/$packageName"
    }
    
    // 3. CRITICAL HARDENING: Validate the CONSTRUCTED path immediately
    // Ensures that even if regex passes, the final string is structurally sound per PathRules
    try {
        PathRules.componentsBelowRoot(rawPath)
    } catch (e: PathException) {
        return null // Invalid construction
    }
    
    return rawPath
}

fun MaintenanceRequest.deleteMode(): DeleteMode = when (this) {
    is MaintenanceRequest.ClearExternalAppCache -> DeleteMode.CONTENTS_ONLY
    is MaintenanceRequest.RemoveUninstalledAppData -> DeleteMode.TREE
}

/**
 * Maps a scan item onto a privileged request, or null if the item is not one of the two shapes the privileged path
 * supports (or its path does not match its own package/category). A null is NEVER answered by quietly falling back
 * to some other operation.
 */
fun ScanItem.toMaintenanceRequest(knownPackages: Set<String>): MaintenanceRequest? {
    val pkg = owningPackage ?: return null
    val fingerprint = fingerprint ?: return null
    
    val components = try {
        PathRules.componentsBelowRoot(path)
    } catch (e: PathException) {
        return null
    }
    
    val request: MaintenanceRequest = when (category) {
        CleaningCategory.APP_CACHE -> {
            if (!PathRules.isExternalAppCacheDir(components) || !components[2].equals(pkg, ignoreCase = true)) return null
            MaintenanceRequest.ClearExternalAppCache(pkg, fingerprint)
        }
        CleaningCategory.CORPSE_LEFTOVER -> {
            if (!PathRules.isExternalAppDataRoot(components) || !components[2].equals(pkg, ignoreCase = true)) return null
            val area = if (components[1].equals("obb", ignoreCase = true)) ExternalArea.OBB else ExternalArea.DATA
            MaintenanceRequest.RemoveUninstalledAppData(area, pkg, fingerprint, knownPackages)
        }
        else -> return null
    }
    
    // ROBUST COMPARISON: Normalize both paths for case-insensitive equality check
    // Shared storage (FUSE/sdcardfs) is case-insensitive. Strict == fails on minor casing diffs.
    val targetPath = request.targetPathOrNull() ?: return null
    if (targetPath != path) return null
    
    return request
}

enum class FailureKind {
    /** Nothing was deleted because the target is no longer what the scan saw (gone, replaced, wrong type). */
    PRECONDITION_CHANGED,
    /** CleanForge's own policy refused. */
    REFUSED,
    /** The privileged channel was unusable at the moment of the attempt. */
    UNAVAILABLE,
    /** Stopped at a safety bound before anything was removed. */
    LIMIT,
    /** Stopped on request before anything was removed. */
    CANCELLED,
    /** The operation ran and failed. */
    IO_ERROR
}

/**
 * Structured outcome of a maintenance action. Exactly one of five things happened, and a caller must handle each.
 * Byte counts are LOGICAL file sizes reported by the deleter, not disk blocks.
 */
sealed interface MaintenanceResult {
    val message: String
    /** The goal was reached and the target's state was checked afterwards. */
    data class Success(val entriesRemoved: Int, val bytesFreed: Long, override val message: String = "Deleted") : MaintenanceResult
    /** Some of the goal was reached. */
    data class Partial(val entriesRemoved: Int, val bytesFreed: Long, override val message: String) : MaintenanceResult
    /** The action did not (fully) happen. */
    data class Failure(
        val kind: FailureKind,
        override val message: String,
        val entriesRemoved: Int = 0,
        val bytesFreed: Long = 0L
    ) : MaintenanceResult
    /** Not attempted: the capability registry says the operation cannot be done in the current state. */
    data class Unsupported(val capability: CapabilityStatus, override val message: String) : MaintenanceResult
    /** The action may or may not have happened, or its effect could not be confirmed. */
    data class Unverified(
        override val message: String,
        val entriesRemoved: Int = 0,
        val bytesFreed: Long = 0L
    ) : MaintenanceResult
}

/** What was seen when the target was checked after the action. */
sealed interface PostState {
    data object Gone : PostState
    data object ExistsEmpty : PostState
    data class ExistsNonEmpty(val detail: String) : PostState
    data class Unknown(val why: String) : PostState
}

/**
 * Turns the deleter's status plus the observed end state into the five-way result. Shared by the privileged
 * executor and the standard (app-process) path so both are judged by the same rules. Pure and exhaustively tested.
 */
fun classifyDeletion(mode: DeleteMode, result: DeleteResult, post: PostState): MaintenanceResult {
    val entries = result.entriesRemoved
    val bytes = result.bytesFreed
    val detail = result.detail
    return when (result.status) {
        DeleteStatus.OK -> when (post) {
            PostState.Gone ->
                // For CONTENTS_ONLY the folder is expected to stay; if it vanished too, the contents are gone anyway.
                MaintenanceResult.Success(entries, bytes)
            PostState.ExistsEmpty ->
                if (mode == DeleteMode.CONTENTS_ONLY) {
                    MaintenanceResult.Success(entries, bytes)
                } else {
                    MaintenanceResult.Partial(entries, bytes, "Reported as deleted, but the folder is still there")
                }
            is PostState.ExistsNonEmpty ->
                MaintenanceResult.Partial(
                    entries, bytes,
                    if (mode == DeleteMode.CONTENTS_ONLY) {
                        "Cleared, but files remain. The app may be running and writing again, or some files could not be removed"
                    } else {
                        "Reported as deleted, but ${post.detail}"
                    }
                )
            is PostState.Unknown ->
                MaintenanceResult.Unverified("Reported as deleted, but this could not be confirmed: ${post.why}", entries, bytes)
        }
        DeleteStatus.PARTIAL ->
            MaintenanceResult.Partial(entries, bytes, "Partly cleaned: ${detail.ifEmpty { "some entries could not be removed" }}")
        DeleteStatus.LIMIT_EXCEEDED ->
            if (entries > 0) {
                MaintenanceResult.Partial(entries, bytes, "Stopped at a safety limit: ${detail.ifEmpty { "folder is very large" }}")
            } else {
                MaintenanceResult.Failure(FailureKind.LIMIT, "Stopped at a safety limit: ${detail.ifEmpty { "folder is very large" }}")
            }
        DeleteStatus.CANCELLED ->
            if (entries > 0) {
                MaintenanceResult.Partial(entries, bytes, "Stopped before finishing")
            } else {
                MaintenanceResult.Failure(FailureKind.CANCELLED, "Stopped before anything was removed")
            }
        DeleteStatus.MISSING, DeleteStatus.IDENTITY_CHANGED, DeleteStatus.WRONG_TYPE ->
            MaintenanceResult.Failure(FailureKind.PRECONDITION_CHANGED, detail.ifEmpty { result.status.name })
        DeleteStatus.REFUSED ->
            MaintenanceResult.Failure(FailureKind.REFUSED, detail.ifEmpty { "Refused by the safety rules" })
        DeleteStatus.FAILED ->
            MaintenanceResult.Failure(FailureKind.IO_ERROR, detail.ifEmpty { "Could not delete" }, entries, bytes)
        DeleteStatus.UNAVAILABLE ->
            MaintenanceResult.Failure(FailureKind.UNAVAILABLE, detail.ifEmpty { "The Shizuku service was not reachable" })
        DeleteStatus.TIMED_OUT ->
            MaintenanceResult.Unverified(detail.ifEmpty { "The Shizuku service did not answer in time" }, entries, bytes)
    }
}

/**
 * Looks at a target after an action and says what is there. [stat] and [list] are the reads of whichever backend did
 * the work, so the privileged and the standard path are judged identically. An unreadable target is
 * [PostState.Unknown], never "gone".
 */
suspend fun observePostState(
    target: String,
    mode: DeleteMode,
    stat: suspend (String) -> FsResult<FsStat>,
    list: suspend (String) -> FsResult<DirListing>
): PostState {
    val st = stat(target)
    if (st is FsResult.NotFound) return PostState.Gone
    if (st is FsResult.Denied) return PostState.Unknown(st.why)
    if (st is FsResult.Unavailable) return PostState.Unknown(st.why)
    if (st is FsResult.Failed) return PostState.Unknown(st.why)
    if (mode != DeleteMode.CONTENTS_ONLY) return PostState.ExistsNonEmpty("the folder is still present")
    val l = list(target)
    if (l is FsResult.NotFound) return PostState.Gone
    if (l is FsResult.Denied) return PostState.Unknown(l.why)
    if (l is FsResult.Unavailable) return PostState.Unknown(l.why)
    if (l is FsResult.Failed) return PostState.Unknown(l.why)
    if (l !is FsResult.Ok) return PostState.Unknown("unexpected result")
    val listing = l.value
    return when {
        listing.names.isEmpty() && !listing.truncated -> PostState.ExistsEmpty
        listing.truncated -> PostState.ExistsNonEmpty("many entries remain")
        else -> PostState.ExistsNonEmpty("${listing.names.size} entries remain")
    }
}
