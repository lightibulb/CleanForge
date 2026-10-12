package com.cleanforge.core.shizuku

sealed interface ShizukuState {
    data object NotInstalled : ShizukuState
    data object NotRunning : ShizukuState

    /** Running, but CleanForge has not been allowed yet. The permission dialog can still be shown. */
    data object PermissionDenied : ShizukuState

    /**
     * The user chose "deny and don't ask again" (Shizuku API: shouldShowRequestPermissionRationale() is true).
     * Asking again shows nothing; the user must allow CleanForge inside the Shizuku app.
     */
    data object PermissionPermanentlyDenied : ShizukuState

    data object Disconnected : ShizukuState

    /** The running Shizuku server predates version 11 and cannot host user services. */
    data object ServerTooOld : ShizukuState

    /**
     * [uid] is 2000 for adb/shell mode, 0 if the user started Shizuku with root. This only says the binder is alive and
     * CleanForge may use it; it does NOT prove any operation works (see CapabilityRegistry).
     */
    data class Authorized(val uid: Int, val serverVersion: Int = 0) : ShizukuState {
        val isRoot: Boolean get() = uid == 0
    }

    data class Error(val message: String) : ShizukuState
}
