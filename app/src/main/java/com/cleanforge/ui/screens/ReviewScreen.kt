package com.cleanforge.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleanforge.core.scanner.ScanMode
import com.cleanforge.ui.CleanViewModel
import com.cleanforge.ui.components.NoticeCard
import com.cleanforge.ui.components.ScanBar
import com.cleanforge.ui.components.resultsSection

/** On-demand tools whose results are personal-data candidates: nothing here is ever pre-selected. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(vm: CleanViewModel, modifier: Modifier = Modifier) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val results = ui.items.filter { !it.category.isQuick }

    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text("Review") }, scrollBehavior = scroll) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                ) {
                    Text(
                        "These tools only suggest things for YOU to look at. Large, old or duplicated files are " +
                            "often exactly what you want to keep, so none of them are ticked for you.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            item {
                ScanBar(
                    scanning = ui.scanning,
                    label = ui.scanLabel,
                    buttonText = "Find large files, duplicates, logs, empty folders",
                    hint = "Duplicate detection reads file contents on this phone (nothing is uploaded) and can " +
                        "take a few minutes on a lot of photos or videos.",
                    onScan = { vm.scan(ScanMode.ON_DEMAND) },
                    onCancel = vm::cancelScan
                )
            }
            if (ui.notices.isNotEmpty()) item { NoticeCard(ui.notices) }
            if (!ui.scanning && results.isEmpty()) {
                item {
                    Text(
                        "No results yet.",
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
