package com.cleanforge.core.model

enum class PermissionNeed { NONE, ALL_FILES, SHIZUKU_OPERATION }

data class PermissionNote(
    val need: PermissionNeed,
    val required: Boolean,
    val available: Boolean,
    val detail: String
) {
    val blocking: Boolean get() = required && !available
}
