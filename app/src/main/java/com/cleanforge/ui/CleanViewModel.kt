package com.cleanforge.ui

import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleanforge.core.backend.Capabilities
import com.cleanforge.core.cleaning.CleaningProgress
import com.cleanforge.core.cleaning.PlanBuilder
import com.cleanforge.core.model.CleaningPlan
import com.cleanforge.core.model.CleaningReport
import com.cleanforge.core.model.ItemStatus
import com.cleanforge.core.model.ScanItem
import com.cleanforge.core.model.confirmedByUser
import com.cleanforge.core.scanner.ScanMode
import com.cleanforge.core.shizuku.ShizukuManager
import com.cleanforge.core.shizuku.ShizukuState
import com.cleanforge.data.repository.ScanProgress
import com.cleanforge.data.repository.ScanRepository
import com.cleanforge.domain.usecase.ExecuteCleaningPlanUseCase
import com.cleanforge.domain.usecase.ScanDeviceUseCase
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

data class CleaningUi(
    val done: Int = 0,
    val total: Int = 0,
    val freedBytes: Long = 0L,
    val current: String = "",
    val report: CleaningReport? = null
)

data class CleanUiState(
    val freeBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val hasAllFilesAccess: Boolean = false,
    val shizuku: ShizukuState = ShizukuState.NotRunning,
    val scanning: Boolean = false,
    val scanLabel: String = "",
    val notices: List<String> = emptyList(),
    val items: List<ScanItem> = emptyList(),
    val selected: Set<String> = emptySet(),
    val plan: CleaningPlan? = null,
    val cleaning: CleaningUi? = null
) {
    val usedFraction: Float
        get() = if (totalBytes > 0) ((totalBytes - freeBytes).toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    val selectedBytes: Long get() = items.filter { it.path in selected }.sumOf { it.sizeBytes }
    val busy: Boolean get() = scanning || cleaning != null
}

@HiltViewModel
class CleanViewModel @Inject constructor(
    private val scanDevice: ScanDeviceUseCase,
    private val repository: ScanRepository,
    private val planBuilder: PlanBuilder,
    private val executePlan: ExecuteCleaningPlanUseCase,
    private val shizuku: ShizukuManager,
    private val capabilities: Capabilities
) : ViewModel() {

    private val _ui = MutableStateFlow(CleanUiState())
    val ui: StateFlow<CleanUiState> = _ui.asStateFlow()

    private var scanJob: Job? = null

    init {
        refreshDevice()
        viewModelScope.launch {
            shizuku.state.collect { st -> _ui.update { it.copy(shizuku = st) } }
        }
        viewModelScope.launch {
            repository.items.collect { items ->
                val present = items.mapTo(HashSet<String>()) { it.path }
                _ui.update { s -> s.copy(items = items, selected = s.selected.filterTo(HashSet<String>()) { it in present }) }
            }
        }
    }

    fun onResume() {
        shizuku.refresh()
        refreshDevice()
    }

    private fun refreshDevice() {
        viewModelScope.launch(Dispatchers.IO) {
            val stat = runCatching { StatFs(Environment.getExternalStorageDirectory().absolutePath) }.getOrNull()
            val allFiles = capabilities.hasAllFilesAccess()
            _ui.update {
                it.copy(
                    freeBytes = stat?.availableBytes ?: it.freeBytes,
                    totalBytes = stat?.totalBytes ?: it.totalBytes,
                    hasAllFilesAccess = allFiles
                )
            }
        }
    }

    // ---------- SCAN + CLASSIFY ----------

    fun scan(mode: ScanMode) {
        if (_ui.value.busy) return
        refreshDevice()
        _ui.update { it.copy(scanning = true, scanLabel = "Starting…", notices = emptyList()) }
        scanJob = viewModelScope.launch {
            val notices = ArrayList<String>()
            try {
                scanDevice(mode).collect { p ->
                    when (p) {
                        is ScanProgress.ScannerStarted ->
                            _ui.update { it.copy(scanLabel = "${p.name} (${p.index}/${p.total})") }
                        is ScanProgress.ScannerSkipped -> notices += "${p.name}: ${p.reason}"
                        is ScanProgress.ScannerFailed -> notices += "${p.name} failed: ${p.reason}"
                        else -> Unit
                    }
                }
                if (mode == ScanMode.QUICK) selectRecommended()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                notices += "Scan failed: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                _ui.update { it.copy(scanning = false, scanLabel = "", notices = notices.toList()) }
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
    }

    // ---------- SELECTION ----------

    fun toggle(path: String) {
        _ui.update { s -> s.copy(selected = if (path in s.selected) s.selected - path else s.selected + path) }
    }

    /** Ticks only well-evidenced app caches. Everything else must be ticked by the user. */
    fun selectRecommended() {
        _ui.update { s -> s.copy(selected = s.selected + s.items.filter { it.isPreselected }.map { it.path }) }
    }

    fun clearSelection() {
        _ui.update { it.copy(selected = emptySet()) }
    }

    // ---------- VALIDATE + PLAN -> USER CONFIRMATION ----------

    fun requestClean() {
        val s = _ui.value
        if (s.busy || s.plan != null) return
        val chosen = s.items.filter { it.path in s.selected }
        if (chosen.isEmpty()) return
        _ui.update { it.copy(plan = planBuilder.build(chosen, s.items)) }
    }

    fun dismissPlan() {
        _ui.update { it.copy(plan = null) }
    }

    // ---------- REVALIDATE -> DELETE -> VERIFY -> REPORT ----------

    fun confirmClean() {
        val plan = _ui.value.plan ?: return
        if (_ui.value.busy) return
        if (plan.isEmpty) {
            dismissPlan()
            return
        }
        _ui.update { it.copy(plan = null, cleaning = CleaningUi(total = plan.items.size)) }
        viewModelScope.launch {
            try {
                executePlan(plan.confirmedByUser()).collect { p ->
                    when (p) {
                        is CleaningProgress.Progress ->
                            _ui.update { it.copy(cleaning = CleaningUi(p.done, p.total, p.freedBytes, p.currentName)) }
                        is CleaningProgress.Completed -> {
                            val gone = p.report.outcomes
                                .filter { it.status == ItemStatus.DELETED }
                                .mapTo(HashSet<String>()) { it.item.path }
                            repository.forget(gone)
                            _ui.update {
                                it.copy(
                                    selected = it.selected - gone,
                                    cleaning = CleaningUi(total = p.report.attempted, report = p.report)
                                )
                            }
                            refreshDevice()
                        }
                        else -> Unit
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(
                        cleaning = null,
                        notices = it.notices + "Cleaning stopped: ${t.message ?: t.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    fun dismissReport() {
        _ui.update { it.copy(cleaning = null) }
    }

    fun requestShizukuPermission() {
        shizuku.requestPermission()
    }
}
