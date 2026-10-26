package com.cleanforge.ui

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleanforge.core.backend.Capabilities
import com.cleanforge.core.cleaning.CleaningProgress
import com.cleanforge.core.cleaning.PermissionAnnotator
import com.cleanforge.core.cleaning.PlanBuilder
import com.cleanforge.core.diagnostics.DiagnosticExporter
import com.cleanforge.core.diagnostics.DiagnosticScanner
import com.cleanforge.core.model.CleaningCategory
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
import com.cleanforge.domain.usecase.CollectDiagnosticReportUseCase
import com.cleanforge.domain.usecase.ExecuteCleaningPlanUseCase
import com.cleanforge.domain.usecase.ScanDeviceUseCase
import com.cleanforge.domain.usecase.StorageBreakdown
import com.cleanforge.domain.usecase.StorageBreakdownUseCase
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
    val stopping: Boolean = false
)

data class StorageUi(
    val expanded: Boolean = false,
    val loading: Boolean = false,
    val breakdown: StorageBreakdown? = null,
    val error: String? = null
)

data class CleanUiState(
    val freeBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val hasAllFilesAccess: Boolean = false,
    val shizuku: ShizukuState = ShizukuState.NotRunning,
    val capabilities: CapabilityReport? = null,
    val scanning: Boolean = false,
    val scanLabel: String = "",
    val scanned: Boolean = false,
    val notices: List<String> = emptyList(),
    val scanSummary: List<String> = emptyList(),
    val storage: StorageUi = StorageUi(),
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
    private val registry: CapabilityRegistry,
    private val storageBreakdown: StorageBreakdownUseCase,
    private val permissionAnnotator: PermissionAnnotator,
    private val diagnosticReport: CollectDiagnosticReportUseCase
) : ViewModel() {

    private val _ui = MutableStateFlow(CleanUiState())
    val ui: StateFlow<CleanUiState> = _ui.asStateFlow()

    private var scanJob: Job? = null
    private var cleanJob: Job? = null

    init {
        refreshDevice()
        viewModelScope.launch { shizuku.state.collect { st -> _ui.update { it.copy(shizuku = st) } } }
        viewModelScope.launch { registry.report.collect { r -> _ui.update { it.copy(capabilities = r) } } }
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

    fun recheckShizuku() {
        viewModelScope.launch(Dispatchers.IO) {
            shizuku.refresh()
            registry.probe()
        }
    }

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

    fun scan(mode: ScanMode) {
        if (_ui.value.busy) return
        refreshDevice()
        _ui.update { it.copy(scanning = true, scanLabel = "Starting…", notices = emptyList(), scanSummary = emptyList()) }
        scanJob = viewModelScope.launch {
            val notices = ArrayList<String>()
            var summary: List<String> = emptyList()
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
                        is ScanProgress.Finished -> {
                            finished = true
                            summary = p.summaries.map { s ->
                                if (s.count == 0) "${s.name}: nothing found"
                                else "${s.name}: ${s.count} found (${formatBytes(s.bytes)})"
                            }
                        }
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
                        notices = notices.toList(),
                        scanSummary = summary
                    )
                }
                if (_ui.value.storage.expanded) loadStorage()
            }
        }
    }

    fun cancelScan() { scanJob?.cancel() }

    fun toggle(path: String) {
        _ui.update { s -> s.copy(selected = if (path in s.selected) s.selected - path else s.selected + path) }
    }

    fun selectRecommended() {
        _ui.update { s -> s.copy(selected = s.selected + s.items.filter { it.isPreselected }.map { it.path }) }
    }

    fun clearSelection() { _ui.update { it.copy(selected = emptySet()) } }

    fun selectEverythingAndReview(candidates: List<ScanItem>) {
        if (_ui.value.busy || candidates.isEmpty()) return
        val keepers = HashSet<String>()
        candidates.filter { it.category == CleaningCategory.DUPLICATE && it.groupId != null }
            .groupBy { it.groupId }
            .values
            .forEach { group -> group.minByOrNull { it.lastModified }?.let { keepers += it.path } }
        val paths = candidates.map { it.path }.filterTo(HashSet<String>()) { it !in keepers }
        _ui.update { it.copy(selected = paths) }
        requestClean()
    }

    fun toggleStorage() {
        val open = !_ui.value.storage.expanded
        _ui.update { it.copy(storage = it.storage.copy(expanded = open)) }
        if (open) loadStorage()
    }

    fun loadStorage() {
        val s = _ui.value
        if (s.storage.loading) return
        _ui.update { it.copy(storage = it.storage.copy(loading = true, error = null)) }
        viewModelScope.launch {
            try {
                val used = (s.totalBytes - s.freeBytes).coerceAtLeast(0L)
                val breakdown = storageBreakdown(used, _ui.value.items)
                _ui.update { it.copy(storage = it.storage.copy(loading = false, breakdown = breakdown)) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _ui.update { it.copy(storage = it.storage.copy(loading = false, error = t.message ?: "Could not read storage")) }
            }
        }
    }

    fun requestClean() {
        val s = _ui.value
        if (s.busy || s.plan != null) return
        val chosen = s.items.filter { it.path in s.selected }
        if (chosen.isEmpty()) return
        val base = planBuilder.build(chosen, s.items)
        _ui.update { it.copy(plan = base) }
        viewModelScope.launch(Dispatchers.IO) {
            val annotated = permissionAnnotator.annotate(base)
            _ui.update { if (it.plan === base) it.copy(plan = annotated) else it }
        }
    }

    fun dismissPlan() { _ui.update { it.copy(plan = null) } }

    fun confirmClean() {
        val plan = _ui.value.plan ?: return
        if (_ui.value.busy) return
        if (plan.isEmpty) { dismissPlan(); return }
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
                        is CleaningProgress.Completed -> { completed = true; applyReport(p.report) }
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

    fun cancelClean() {
        _ui.update { it.copy(cleaning = it.cleaning?.copy(stopping = true)) }
        cleanJob?.cancel()
    }

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

    fun dismissReport() { _ui.update { it.copy(cleaning = null) } }

    fun requestShizukuPermission() {
        viewModelScope.launch(Dispatchers.IO) { shizuku.requestPermission() }
    }

    fun showDiagnostics(context: Context, redactPaths: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            val s = _ui.value
            val scanners = s.scanSummary.map { line ->
                val name = line.substringBefore(':')
                val count = Regex("(\\d+) found").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                DiagnosticScanner(
                    name = name,
                    status = if (count > 0) "ran" else "ran (nothing found)",
                    itemCount = count,
                    bytes = 0L,
                    reason = null
                )
            }
            val report = diagnosticReport(
                totalBytes = s.totalBytes,
                freeBytes = s.freeBytes,
                scanners = scanners,
                notices = s.notices,
                redactPaths = redactPaths
            )
            val text = DiagnosticExporter.toText(report)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, "CleanForge diagnostic report")
            }
            val chooser = Intent.createChooser(intent, "Share report").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }
    }
}
