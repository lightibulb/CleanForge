package com.cleanforge.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cleanforge.core.model.CleaningPlan
import com.cleanforge.core.model.CleaningReport
import com.cleanforge.core.model.ItemStatus
import com.cleanforge.core.model.PermissionNeed
import com.cleanforge.core.model.PermissionNote
import com.cleanforge.core.model.RejectedItem
import com.cleanforge.core.model.Reversibility
import com.cleanforge.ui.CleaningUi
import com.cleanforge.ui.ConfirmationEvidence
import com.cleanforge.ui.confirmationEvidence
import com.cleanforge.ui.formatBytes

/**
 * USER CONFIRMATION step. Every item that will be deleted is listed in full (no "and N more"): its complete path,
 * why it was flagged, confidence, risk, what exactly happens, whether a permission is currently missing, and for
 * duplicates which copy stays. Refused items are listed with their full path and reason too.
 */
@Composable
fun ConfirmDialog(
    plan: CleaningPlan,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onOpenAllFilesSettings: () -> Unit = {},
    onOpenShizuku: () -> Unit = {}
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.DeleteForever, contentDescription = null) },
        title = {
            Text(if (plan.isEmpty) "Nothing can be deleted" else "Delete ${plan.items.size} item(s)?")
        },
        text = {
            LazyColumn(Modifier.fillMaxWidth()) {
                if (!plan.isEmpty) {
                    item(key = "summary") { PlanSummary(plan) }
                }
                if (plan.hasBlockingPermissions) {
                    item(key = "perm") {
                        PermissionBanner(
                            plan = plan,
                            onOpenAllFilesSettings = onOpenAllFilesSettings,
                            onOpenShizuku = onOpenShizuku
                        )
                    }
                }
                items(plan.items, key = { "delete:" + it.path }) {
                    EvidenceBlock(it.confirmationEvidence(), plan.permissionNotes[it.path])
                }
                if (plan.rejected.isNotEmpty()) {
                    item(key = "refused-header") {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "${plan.rejected.size} selected item(s) were refused by safety rules and will NOT be deleted:",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    items(
                        plan.rejected,
                        key = { "refused:" + it.item.category.name + ":" + it.item.path + ":" + it.reason }
                    ) { RefusedBlock(it) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !plan.isEmpty && !plan.allItemsBlocked,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text(if (plan.allItemsBlocked) "Nothing can run right now" else "Delete permanently")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PlanSummary(plan: CleaningPlan) {
    Column {
        Text(
            (if (plan.hasLowerBoundSizes) "Frees at least ${formatBytes(plan.totalBytes)}"
            else "Frees about ${formatBytes(plan.totalBytes)}") +
                " (file sizes, not disk blocks).",
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        if (plan.items.any { it.reversibility == Reversibility.NOT_REVERSIBLE }) {
            Text("Deleted for good: CleanForge has no recycle bin.")
            Spacer(Modifier.height(4.dp))
        }
        if (plan.hasNonSafeItems) {
            Text(
                "Some items are not plain caches and may be personal. Check every path below before confirming.",
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(4.dp))
        }
        if (plan.needsShizuku) {
            Text("${plan.items.count { it.requiresShizuku }} item(s) are removed through Shizuku.")
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PermissionBanner(
    plan: CleaningPlan,
    onOpenAllFilesSettings: () -> Unit,
    onOpenShizuku: () -> Unit
) {
    val anyAllFiles = plan.permissionNotes.values.any { it.blocking && it.need == PermissionNeed.ALL_FILES }
    val anyShizuku = plan.permissionNotes.values.any { it.blocking && it.need == PermissionNeed.SHIZUKU_OPERATION }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            "Some items cannot run right now and will be skipped.",
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (anyAllFiles) {
                TextButton(onClick = onOpenAllFilesSettings) { Text("Open All-files settings") }
            }
            if (anyShizuku) {
                TextButton(onClick = onOpenShizuku) { Text("Open Shizuku") }
            }
        }
    }
}

@Composable
private fun EvidenceBlock(e: ConfirmationEvidence, permission: PermissionNote?) {
    val small = MaterialTheme.typography.bodySmall
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(e.path, style = small, fontWeight = FontWeight.SemiBold)
        Text("${e.category} · ${formatBytes(e.sizeBytes)}", style = small)
        Text("Why flagged: ${e.whyFlagged}", style = small)
        Text("Confidence: ${e.confidence} · Risk: ${e.risk}", style = small)
        e.owningPackage?.let { Text("App: $it", style = small) }
        Text(e.whatHappens, style = small)
        if (e.flags.isNotEmpty()) Text(e.flags.joinToString(" · "), style = small)
        e.keepPath?.let { keep ->
            Text("KEEP: $keep", style = small, fontWeight = FontWeight.SemiBold)
            Text("DELETE CANDIDATE: ${e.path}", style = small, fontWeight = FontWeight.SemiBold)
        }
        if (permission != null) {
            Text(
                (if (permission.blocking) "BLOCKED: " else "") + permission.detail,
                style = small,
                color = if (permission.blocking) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider()
    }
}

@Composable
private fun RefusedBlock(r: RejectedItem) {
    val small = MaterialTheme.typography.bodySmall
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(r.item.path, style = small, fontWeight = FontWeight.SemiBold)
        Text("Refused: ${r.reason}", style = small)
    }
}

@Composable
fun ProgressDialog(state: CleaningUi, onStop: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* not dismissible while deleting */ },
        title = { Text(if (state.stopping) "Stopping…" else "Cleaning…") },
        text = {
            Column {
                LinearProgressIndicator(
                    progress = { if (state.total > 0) state.done.toFloat() / state.total else 0f },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("${state.done} of ${state.total} · freed ${formatBytes(state.freedBytes)}")
                if (state.current.isNotEmpty()) {
                    Text(state.current, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                if (state.stopping) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Finishing the item in progress. Anything already removed stays removed.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onStop, enabled = !state.stopping) { Text("Stop") } }
    )
}

@Composable
fun ReportDialog(report: CleaningReport, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
        title = { Text(if (report.fullySuccessful) "All done" else "Finished with issues") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Freed ${formatBytes(report.bytesFreed)} (file sizes reported by the cleaner)",
                    fontWeight = FontWeight.SemiBold
                )
                if (report.unconfirmedBytes > 0) {
                    Text("${formatBytes(report.unconfirmedBytes)} of that could not be confirmed afterwards.")
                }
                Text(
                    "Deleted ${report.deleted} · partly ${report.partial} · unconfirmed ${report.unverified} · " +
                        "skipped ${report.skipped} · failed ${report.failed}"
                )
                val problems = report.outcomes.filter { it.status != ItemStatus.DELETED }
                if (problems.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    problems.take(MAX_LISTED).forEach {
                        Text("• ${it.item.path}: ${it.message}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (problems.size > MAX_LISTED) {
                        Text("…and ${problems.size - MAX_LISTED} more", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

private const val MAX_LISTED = 6
