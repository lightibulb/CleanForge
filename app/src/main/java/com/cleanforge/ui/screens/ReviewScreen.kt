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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleanforge.core.scanner.ScanMode
import com.cleanforge.ui.CleanViewModel
import com.cleanforge.ui.components.NoticeCard
import com.cleanforge.ui.components.ScanBar
import com.cleanforge.ui.components.resultsSection

/** Review tab: things that only SUGGEST (large files, duplicates, old logs, empty folders, app media). None is ticked. */
@Composable
fun ReviewScreen(vm: CleanViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val review = remember(ui.items) { ui.items.filterNot { it.category.isQuick } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp)
    ) {
        item(key = "intro") {
            Text(
                "These tools only point things out for you to look at. Large, old or duplicated files are often " +
                    "exactly what you want to keep, so none of them is ticked for you.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item(key = "scanbar") {
            ScanBar(
                scanning = ui.scanning,
                label = ui.scanLabel,
                buttonText = "Find large files, duplicates and more",
                hint = "Reads file contents on this phone (nothing is uploaded) and can take a few minutes on a lot " +
                    "of photos or videos.",
                onScan = { vm.scan(ScanMode.ON_DEMAND) },
                onCancel = vm::cancelScan
            )
        }
        if (ui.notices.isNotEmpty()) item(key = "notices") { NoticeCard(ui.notices) }
        if (ui.scanned && !ui.scanning && review.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Nothing was suggested. Run the search above to look for large files and duplicates.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        resultsSection(review, ui.selected, vm::toggle)
    }
}
