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
import com.cleanforge.core.model.ItemOutcome
import com.cleanforge.core.model.ItemStatus
import com.cleanforge.core.model.ScanItem
import com.cleanforge.core.model.confirmedByUser
import com.cleanforge.core.privileged.CapabilityRegistry
import com.cleanforge.core.privileged.CapabilityReport
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
    val report: CleaningReport? = null,
    /** The user pressed Stop; the run ends after the item in flight. */
    val stopping: Boolean = false
)

data class CleanUiState(
    val freeBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val hasAllFilesAccess: Boolean = false,
    val shizuku: ShizukuState = ShizukuState.NotRunning,
    /** Per-operation capability, as probed. Null until the first probe finished. */
    val capabilities: CapabilityReport? = null,
    val scanning: Boolean = false,
    val scanLabel: String = "",
    /** True once a scan has finished, so "no results" can be told apart from "never scanned". */
    val scanned: Boolean = false,
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
    private val capabilities: Capabilities,
    private val registry: CapabilityRegistry
) : ViewModel() {

    private val _ui = MutableStateFlow(CleanUiState())
    val ui: StateFlow<CleanUiState> = _ui.asStateFlow()

    private var scanJob: Job? = null
    private var cleanJob: Job? = null

    init {
        refreshDevice()
        viewModelScope.launch {
            shizuku.state.collect { st -> _ui.update { it.copy(shizuku = st) } }
        }
        viewModelScope.launch {
            registry.report.collect { r -> _ui.update { it.copy(capabilities = r) } }
        }
        viewModelScope.launch {
            repository.items.collect { items ->
                val present = items.mapTo(HashSet<String>()) { it.path }
                _ui.update { s -> s.copy(items = items, selected = s.selected.filterTo(HashSet<String>()) { it in present }) }
            }
        }
        recheckShizuku()
    }

    fun onResume() {
        recheckShizuku()
        refreshDevice()
    }

    /** Re-reads Shizuku and re-probes capabilities. Binder calls: always off the main thread. */
    fun recheckShizuku() {
        viewModelScope.launch(Dispatchers.IO) {
            shizuku.refresh()
            registry.probe()
        }
    }

    /** The "Check again" button: a probe that ignores the recent-result shortcut. */
    fun forceRecheckShizuku() {
        viewModelScope.launch(Dispatchers.IO) {
            shizuku.refresh()
            registry.probe(force = true)
        }
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
            var finished = false
            try {
                scanDevice(mode).collect { p ->
                    when (p) {
                        is ScanProgress.ScannerStarted ->
                            _ui.update { it.copy(scanLabel = "${p.name} (${p.index}/${p.total})") }
                        is ScanProgress.ScannerSkipped -> notices += "${p.name}: ${p.reason}"
                        is ScanProgress.ScannerFailed -> notices += "${p.name} failed: ${p.reason}"
                        is ScanProgress.ScannerIncomplete ->
                            notices += "${p.name} is incomplete: ${p.reasons.joinToString("; ")}"
                        is ScanProgress.Finished -> finished = true
                        else -> Unit
                    }
                }
                if (mode == ScanMode.QUICK) selectRecommended()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                notices += "Scan failed: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                _ui.update {
                    it.copy(
                        scanning = false,
                        scanLabel = "",
                        scanned = it.scanned || finished,
                        notices = notices.toList()
                    )
                }
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
        cleanJob = viewModelScope.launch {
            val outcomes = ArrayList<ItemOutcome>()
            var inFlight: ScanItem? = null
            var completed = false
            var stoppedBecause = "the cleanup was stopped"
            try {
                executePlan(plan.confirmedByUser()).collect { p ->
                    when (p) {
                        is CleaningProgress.ItemStarted -> inFlight = p.item
                        is CleaningProgress.Progress -> {
                            p.outcome?.let { outcomes += it }
                            inFlight = null
                            _ui.update {
                                it.copy(
                                    cleaning = (it.cleaning ?: CleaningUi(total = p.total)).copy(
                                        done = p.done,
                                        total = p.total,
                                        freedBytes = p.freedBytes,
                                        current = p.currentName
                                    )
                                )
                            }
                        }
                        is CleaningProgress.Completed -> {
                            completed = true
                            applyReport(p.report)
                        }
                        else -> Unit
                    }
                }
            } catch (ce: CancellationException) {
                stoppedBecause = "you stopped the cleanup"
                throw ce
            } catch (t: Throwable) {
                stoppedBecause = "the cleanup stopped (${t.message ?: t.javaClass.simpleName})"
                _ui.update { it.copy(notices = it.notices + "Cleaning stopped: ${t.message ?: t.javaClass.simpleName}") }
            } finally {
                if (!completed) applyReport(partialReport(plan, outcomes, inFlight, stoppedBecause))
            }
        }
    }

    /** Stops after the item in flight. Whatever was already done is reported; nothing is guessed. */
    fun cancelClean() {
        _ui.update { it.copy(cleaning = it.cleaning?.copy(stopping = true)) }
        cleanJob?.cancel()
    }

    /**
     * A stopped or failed run still gets an honest report: finished items keep their outcome, the item that was in
     * flight is UNVERIFIED (it may or may not have been touched), and the rest are marked not attempted.
     */
    private fun partialReport(plan: CleaningPlan, outcomes: List<ItemOutcome>, inFlight: ScanItem?, why: String): CleaningReport {
        val reported = outcomes.mapTo(HashSet<String>()) { it.item.path }
        val all = ArrayList<ItemOutcome>(outcomes)
        if (inFlight != null && inFlight.path !in reported) {
            all += ItemOutcome(
                inFlight,
                ItemStatus.UNVERIFIED,
                "Stopped while this item was being cleaned ($why). It may be partly or fully cleaned. Scan again to see what is left."
            )
            reported += inFlight.path
        }
        for (item in plan.items) {
            if (item.path !in reported) all += ItemOutcome(item, ItemStatus.SKIPPED, "Not attempted: $why")
        }
        return CleaningReport(all)
    }

    private fun applyReport(report: CleaningReport) {
        val gone = report.outcomes
            .filter { it.status == ItemStatus.DELETED }
            .mapTo(HashSet<String>()) { it.item.path }
        repository.forget(gone)
        _ui.update {
            it.copy(
                selected = it.selected - gone,
                cleaning = CleaningUi(total = report.attempted, report = report)
            )
        }
        refreshDevice()
    }

    fun dismissReport() {
        _ui.update { it.copy(cleaning = null) }
    }

    /** Shows the permission dialog only when it can appear; a "don't ask again" denial needs the Shizuku app. */
    fun requestShizukuPermission() {
        viewModelScope.launch(Dispatchers.IO) {
            shizuku.requestPermission()
        }
    }
}
