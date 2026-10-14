package com.cleanforge.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleanforge.core.scanner.ScanMode
import com.cleanforge.ui.CleanViewModel
import com.cleanforge.ui.components.AllFilesCard
import com.cleanforge.ui.components.NoticeCard
import com.cleanforge.ui.components.ScanBar
import com.cleanforge.ui.components.ShizukuCard
import com.cleanforge.ui.components.StorageHero
import com.cleanforge.ui.components.openAllFilesSettings
import com.cleanforge.ui.components.openShizukuApp
import com.cleanforge.ui.components.resultsSection

/** Quick tab: storage, access status, and the junk the quick scanners found (caches, leftovers, partial downloads...). */
@Composable
fun CleanScreen(vm: CleanViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val quick = remember(ui.items) { ui.items.filter { it.category.isQuick } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Room for the floating "Review" button so it never covers the last row.
        contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp)
    ) {
        item(key = "storage") { StorageHero(ui.freeBytes, ui.totalBytes, ui.usedFraction) }
        if (!ui.hasAllFilesAccess) {
            item(key = "allfiles") { AllFilesCard(onOpenSettings = { openAllFilesSettings(context) }) }
        }
        item(key = "shizuku") {
            ShizukuCard(
                state = ui.shizuku,
                report = ui.capabilities,
                onRequestPermission = vm::requestShizukuPermission,
                onOpenShizuku = { openShizukuApp(context) },
                onRecheck = vm::forceRecheckShizuku
            )
        }
        item(key = "scanbar") {
            ScanBar(
                scanning = ui.scanning,
                label = ui.scanLabel,
                buttonText = "Scan for junk",
                hint = "Looks for app caches, leftovers of uninstalled apps, incomplete downloads, APK installers and " +
                    "the thumbnail cache. Nothing is deleted until you review it and confirm.",
                onScan = { vm.scan(ScanMode.QUICK) },
                onCancel = vm::cancelScan
            )
        }
        if (ui.notices.isNotEmpty()) item(key = "notices") { NoticeCard(ui.notices) }
        if (ui.scanned && !ui.scanning && quick.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Nothing to clean was found. If a scanner above could not run, its reason is listed there.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        resultsSection(quick, ui.selected, vm::toggle)
    }
}
