package com.cleanforge.core.backend

import android.os.Environment
import com.cleanforge.core.privileged.CapabilityRegistry
import com.cleanforge.core.privileged.CapabilityStatus
import com.cleanforge.core.privileged.OperationCapability
import com.cleanforge.core.privileged.PrivilegedOperation
import com.cleanforge.core.shizuku.ShizukuManager
import com.cleanforge.core.shizuku.ShizukuState

/** Live answers (never cached) to "what am I allowed to do right now?". */
interface Capabilities {
    fun hasAllFilesAccess(): Boolean

    /** Shizuku is running and CleanForge may use it. Says nothing about any particular operation. */
    fun shizukuAuthorized(): Boolean

    /**
     * Fresh answer for one privileged operation, with an actionable reason. The default only knows whether Shizuku is
     * authorized; the real implementation runs a read-only probe through the capability registry.
     */
    suspend fun readiness(op: PrivilegedOperation): OperationCapability =
        if (shizukuAuthorized()) {
            OperationCapability(op, CapabilityStatus.PREREQUISITES_MET, "Shizuku is authorized")
        } else {
            OperationCapability(op, CapabilityStatus.UNAVAILABLE, "Shizuku is not available right now")
        }
}

class AndroidCapabilities(
    private val shizuku: ShizukuManager,
    private val registry: CapabilityRegistry
) : Capabilities {
    override fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    override fun shizukuAuthorized(): Boolean {
        shizuku.refresh() // permission can be revoked at any time; never trust a stale value
        return shizuku.state.value is ShizukuState.Authorized
    }

    override suspend fun readiness(op: PrivilegedOperation): OperationCapability = registry.probe().of(op)
}
