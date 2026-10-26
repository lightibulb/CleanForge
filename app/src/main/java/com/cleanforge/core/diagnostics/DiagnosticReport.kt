package com.cleanforge.core.diagnostics

import com.cleanforge.core.privileged.CapabilityReport
import com.cleanforge.core.shizuku.ShizukuState

data class DiagnosticScanner(
    val name: String,
    val status: String,
    val itemCount: Int,
    val bytes: Long,
    val reason: String?
)

data class DiagnosticReport(
    val generatedAt: Long,
    val appVersion: String,
    val androidSdk: Int,
    val deviceModel: String,
    val hasAllFilesAccess: Boolean,
    val shizuku: ShizukuState,
    val capabilities: CapabilityReport?,
    val totalBytes: Long,
    val freeBytes: Long,
    val scanners: List<DiagnosticScanner>,
    val notices: List<String>,
    val redactPaths: Boolean
)
