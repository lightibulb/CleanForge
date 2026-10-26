package com.cleanforge.core.diagnostics

import com.cleanforge.core.privileged.CapabilityReport
import com.cleanforge.core.shizuku.ShizukuState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DiagnosticExporterTest {
    private val report = DiagnosticReport(
        generatedAt = 1_700_000_000_000L,
        appVersion = "0.2.1",
        androidSdk = 33,
        deviceModel = "Test Device",
        hasAllFilesAccess = true,
        shizuku = ShizukuState.NotRunning,
        capabilities = null,
        totalBytes = 1L shl 30,
        freeBytes = 100L shl 20,
        scanners = listOf(DiagnosticScanner("Caches", "ran", 3, 500L, null)),
        notices = listOf("Android/data could not be listed (/storage/emulated/0/Android/data)"),
        redactPaths = true
    )

    @Test fun `text contains every section`() {
        val t = DiagnosticExporter.toText(report)
        assertThat(t).contains("diagnostic report")
        assertThat(t).contains("== Storage ==")
        assertThat(t).contains("== Permissions ==")
        assertThat(t).contains("== Scanners ==")
        assertThat(t).contains("== Notices ==")
        assertThat(t).contains("Caches: ran")
    }

    @Test fun `redaction keeps the last path component and hides the rest`() {
        val t = DiagnosticExporter.toText(report)
        assertThat(t).contains("Android/data")
        assertThat(t).contains("/storage/emulated/0/…/data")
        assertThat(t).doesNotContain("/storage/emulated/0/Android/data)")
    }
}
