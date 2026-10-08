package com.cleanforge.core.shizuku

sealed interface ShizukuState {
    data object NotInstalled : ShizukuState
    data object NotRunning : ShizukuState
    data object PermissionDenied : ShizukuState
    data object Disconnected : ShizukuState

    /** [uid] is 2000 for adb/shell mode, 0 if the user started Shizuku with root. */
    data class Authorized(val uid: Int) : ShizukuState {
        val isRoot: Boolean get() = uid == 0
    }

    data class Error(val message: String) : ShizukuState
}
