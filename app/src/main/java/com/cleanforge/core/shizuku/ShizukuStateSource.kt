package com.cleanforge.core.shizuku

import kotlinx.coroutines.flow.StateFlow

/** What the capability code needs from Shizuku's status tracking. [ShizukuManager] is the real one; tests fake it. */
interface ShizukuStateSource {
    val state: StateFlow<ShizukuState>

    /** Re-reads the live Shizuku status. Makes a binder call: never on the main thread. */
    fun refresh()
}
