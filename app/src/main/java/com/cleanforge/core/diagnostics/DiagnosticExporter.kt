package com.cleanforge.core.diagnostics

import com.cleanforge.core.shizuku.ShizukuState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticExporter {

    fun toText(r: DiagnosticReport): String = buildString {
        appendLine("CleanForge diagnostic report")
        appendLine("Generated: ${iso(r.generatedAt)}")
        appendLine("App: ${r.appVersion} · Android SDK ${r.androidSdk} · ${r.deviceModel}")
        appendLine()
        appendLine("== Storage ==")
        appendLine("Total: ${fmt(r.totalBytes)}  Free: ${fmt(r.freeBytes)}")
        appendLine()
        appendLine("== Permissions ==")
        appendLine("All-files access: ${if (r.hasAllFilesAccess) "granted" else "MISSING"}")
        appendLine("Shizuku: ${describe(r.shizuku)}")
        r.capabilities?.let { cap ->
            appendLine("  Service uid: ${cap.serviceUid ?: "none"} · api: ${cap.serviceApiVersion ?: "?"}")
            cap.operations.forEach { (op, c) ->
                appendLine("  - ${op.title}: ${c.status} — ${c.reason}")
            }
        }
        appendLine()
        appendLine("== Scanners ==")
        if (r.scanners.isEmpty()) appendLine("(no scan has run in this session)")
        r.scanners.forEach { s ->
            val size = if (s.bytes > 0) " (${fmt(s.bytes)})" else ""
            val reason = s.reason?.let { " — ${redact(it, r.redactPaths)}" } ?: ""
            appendLine("- ${s.name}: ${s.status}${size}${reason}")
        }
        if (r.notices.isNotEmpty()) {
            appendLine()
            appendLine("== Notices ==")
            r.notices.forEach { appendLine("- ${redact(it, r.redactPaths)}") }
        }
    }

    private fun redact(s: String, on: Boolean): String {
        if (!on) return s
        return s.replace(Regex("/storage/emulated/0/(\\S+)")) { m ->
            "/storage/emulated/0/…/${m.groupValues[1].substringAfterLast('/')}"
        }
    }

    private fun describe(s: ShizukuState): String = when (s) {
        is ShizukuState.Authorized ->
            "authorized (uid ${s.uid}${if (s.isRoot) ", root" else ""}, v${s.serverVersion})"
        ShizukuState.NotInstalled -> "not installed"
        ShizukuState.NotRunning -> "not running"
        ShizukuState.PermissionDenied -> "permission denied"
        ShizukuState.PermissionPermanentlyDenied -> "permission permanently denied"
        ShizukuState.Disconnected -> "disconnected"
        ShizukuState.ServerTooOld -> "server too old"
        is ShizukuState.Error -> "error: ${s.message}"
    }

    private fun fmt(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", b / (1.0 * (1L shl 30)))
        b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / (1.0 * (1L shl 20)))
        b >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
        else -> "$b B"
    }

    private fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
}
