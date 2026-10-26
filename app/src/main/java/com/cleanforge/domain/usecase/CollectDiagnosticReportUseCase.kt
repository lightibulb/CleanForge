package com.cleanforge.domain.usecase

import android.content.Context
import android.os.Build
import com.cleanforge.BuildConfig
import com.cleanforge.core.backend.Capabilities
import com.cleanforge.core.diagnostics.DiagnosticReport
import com.cleanforge.core.diagnostics.DiagnosticScanner
import com.cleanforge.core.privileged.CapabilityRegistry
import com.cleanforge.core.shizuku.ShizukuManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class CollectDiagnosticReportUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capabilities: Capabilities,
    private val registry: CapabilityRegistry,
    private val shizuku: ShizukuManager
) {
    suspend operator fun invoke(
        totalBytes: Long,
        freeBytes: Long,
        scanners: List<DiagnosticScanner>,
        notices: List<String>,
        redactPaths: Boolean = true
    ): DiagnosticReport = DiagnosticReport(
        generatedAt = System.currentTimeMillis(),
        appVersion = BuildConfig.VERSION_NAME,
        androidSdk = Build.VERSION.SDK_INT,
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
        hasAllFilesAccess = capabilities.hasAllFilesAccess(),
        shizuku = shizuku.state.value,
        capabilities = registry.report.value,
        totalBytes = totalBytes,
        freeBytes = freeBytes,
        scanners = scanners,
        notices = notices,
        redactPaths = redactPaths
    )
}
