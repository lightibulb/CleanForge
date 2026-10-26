package com.cleanforge.ui

import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleanforge.core.fs.PathRules
import com.cleanforge.core.history.GrowthReport
import com.cleanforge.core.history.Snapshot
import com.cleanforge.core.inventory.StorageIndex
import com.cleanforge.core.inventory.StorageIndexer
import com.cleanforge.core.inventory.TreemapRoots
import com.cleanforge.data.repository.SnapshotRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AnalyzeUiState(
    val index: StorageIndex? = null,
    val currentPath: String = PathRules.ROOT,
    val loading: Boolean = false,
    val error: String? = null,
    val snapshots: List<Snapshot> = emptyList(),
    val fromTs: Long? = null,
    val toTs: Long? = null,
    val growth: GrowthReport? = null
)

@HiltViewModel
class AnalyzeViewModel @Inject constructor(
    private val indexer: StorageIndexer,
    private val snapshots: SnapshotRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(AnalyzeUiState())
    val ui: StateFlow<AnalyzeUiState> = _ui.asStateFlow()

    private var indexJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (_ui.value.loading) return
        _ui.update { it.copy(loading = true, error = null) }
        indexJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val stat = runCatching { StatFs(Environment.getExternalStorageDirectory().absolutePath) }.getOrNull()
                val total = stat?.totalBytes ?: 0L
                val free = stat?.availableBytes ?: 0L
                val index = indexer.index(PathRules.ROOT, TreemapRoots.DEFAULT, total, free)
                snapshots.saveFrom(index)
                val all = snapshots.all()
                _ui.update {
                    it.copy(
                        index = index,
                        currentPath = PathRules.ROOT,
                        loading = false,
                        snapshots = all,
                        fromTs = all.dropLast(1).lastOrNull()?.timestamp,
                        toTs = all.lastOrNull()?.timestamp,
                        growth = if (all.size >= 2) snapshots.growth(all[all.size - 2].timestamp, all.last().timestamp) else null
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _ui.update { it.copy(loading = false, error = t.message ?: "Could not index storage") }
            }
        }
    }

    fun open(path: String) {
        val index = _ui.value.index ?: return
        val node = index.byPath(path) ?: return
        if (node.depth > index.directories.maxOfOrNull { it.depth }.let { (it ?: node.depth) }) return
        _ui.update { it.copy(currentPath = path) }
    }

    fun up() {
        val cur = _ui.value.currentPath
        if (cur == PathRules.ROOT) return
        _ui.update { it.copy(currentPath = cur.substringBeforeLast('/').ifEmpty { PathRules.ROOT }) }
    }

    fun selectRange(fromTs: Long, toTs: Long) {
        val growth = snapshots.growth(fromTs, toTs)
        _ui.update { it.copy(fromTs = fromTs, toTs = toTs, growth = growth) }
    }
}
