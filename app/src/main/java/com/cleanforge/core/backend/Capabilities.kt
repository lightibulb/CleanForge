package com.cleanforge.core.backend

import android.os.Environment
import com.cleanforge.core.shizuku.ShizukuManager
import com.cleanforge.core.shizuku.ShizukuState

/** Live answers (never cached) to "what am I allowed to do right now?". */
interface Capabilities {
    fun hasAllFilesAccess(): Boolean
    fun shizukuAuthorized(): Boolean
}

class AndroidCapabilities(private val shizuku: ShizukuManager) : Capabilities {
    override fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    override fun shizukuAuthorized(): Boolean {
        shizuku.refresh() // permission can be revoked at any time; never trust a stale value
        return shizuku.state.value is ShizukuState.Authorized
    }
}
