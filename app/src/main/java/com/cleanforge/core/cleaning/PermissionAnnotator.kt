package com.cleanforge.core.cleaning

import com.cleanforge.core.backend.Capabilities
import com.cleanforge.core.model.CleaningCategory
import com.cleanforge.core.model.CleaningPlan
import com.cleanforge.core.model.PermissionNeed
import com.cleanforge.core.model.PermissionNote
import com.cleanforge.core.privileged.PrivilegedOperation
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PermissionAnnotator @Inject constructor(private val capabilities: Capabilities) {

    suspend fun annotate(plan: CleaningPlan): CleaningPlan {
        val allFiles = capabilities.hasAllFilesAccess()
        val shizuku = capabilities.shizukuAuthorized()
        val notes = LinkedHashMap<String, PermissionNote>(plan.items.size)
        var blocking = false
        for (item in plan.items) {
            val note = if (item.requiresShizuku) {
                val op = when (item.category) {
                    CleaningCategory.CORPSE_LEFTOVER -> PrivilegedOperation.REMOVE_UNINSTALLED_APP_DATA
                    else -> PrivilegedOperation.CLEAR_EXTERNAL_APP_CACHE
                }
                if (!shizuku) {
                    PermissionNote(
                        need = PermissionNeed.SHIZUKU_OPERATION,
                        required = true,
                        available = false,
                        detail = "Shizuku is not available right now; this item will be skipped."
                    )
                } else {
                    val cap = capabilities.readiness(op)
                    PermissionNote(
                        need = PermissionNeed.SHIZUKU_OPERATION,
                        required = true,
                        available = cap.canAttempt,
                        detail = if (cap.canAttempt) "Shizuku is ready for ${op.title}."
                        else "Shizuku: ${cap.status} — ${cap.reason}. This item will be skipped."
                    )
                }
            } else if (!allFiles) {
                PermissionNote(
                    need = PermissionNeed.ALL_FILES,
                    required = true,
                    available = false,
                    detail = "All-files access is missing; this item will be skipped."
                )
            } else {
                PermissionNote(
                    need = PermissionNeed.ALL_FILES,
                    required = true,
                    available = true,
                    detail = "All-files access is granted."
                )
            }
            notes[item.path] = note
            if (note.blocking) blocking = true
        }
        return plan.copy(permissionNotes = notes, hasBlockingPermissions = blocking)
    }
}
