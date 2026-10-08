package com.cleanforge.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanScreen(vm: CleanViewModel, modifier: Modifier = Modifier) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val results = ui.items.filter { it.category.isQuick }

    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text("CleanForge") }, scrollBehavior = scroll) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            item { StorageHero(ui.freeBytes, ui.totalBytes, ui.usedFraction) }
            if (!ui.hasAllFilesAccess) {
                item { AllFilesCard(onOpenSettings = { openAllFilesSettings(context) }) }
            }
            item {
                ShizukuCard(
                    state = ui.shizuku,
                    onRequestPermission = vm::requestShizukuPermission,
                    onOpenShizuku = { openShizukuApp(context) }
                )
            }
            item {
                ScanBar(
                    scanning = ui.scanning,
                    label = ui.scanLabel,
                    buttonText = "Scan",
                    hint = "Looks at app caches, thumbnails, abandoned downloads, installers and leftovers of " +
                        "uninstalled apps. Nothing is deleted until you review and confirm.",
                    onScan = { vm.scan(ScanMode.QUICK) },
                    onCancel = vm::cancelScan
                )
            }
            if (ui.notices.isNotEmpty()) item { NoticeCard(ui.notices) }
            if (!ui.scanning && results.isEmpty()) {
                item {
                    Text(
                        "No results yet. Run a scan to see what could be removed, and why.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(24.dp)
                    )
                }
            }
            resultsSection(results, ui.selected, vm::toggle)
        }
    }
}
