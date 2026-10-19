package com.cleanforge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleanforge.core.memory.ClearMemoryResult
import com.cleanforge.core.memory.RamSnapshot
import com.cleanforge.domain.usecase.AppMemory
import com.cleanforge.domain.usecase.ClearMemoryUseCase
import com.cleanforge.domain.usecase.ObserveRamUseCase
import com.cleanforge.domain.usecase.TopProcessesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The "what is using memory" list behind the memory hero. */
data class ProcessesUi(
    val expanded: Boolean = false,
    val loading: Boolean = false,
    val apps: List<AppMemory> = emptyList(),
    /** Why there is no list (Shizuku missing, service too old...). Null when there is one. */
    val message: String? = null
)

data class RamUiState(
    val snapshot: RamSnapshot? = null,
    val isClearing: Boolean = false,
    val lastClearResult: ClearMemoryResult? = null,
    val processes: ProcessesUi = ProcessesUi()
)

@HiltViewModel
class RamViewModel @Inject constructor(
    private val observeRam: ObserveRamUseCase,
    private val clearMemory: ClearMemoryUseCase,
    private val topProcesses: TopProcessesUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(RamUiState())
    val state: StateFlow<RamUiState> = _state.asStateFlow()

    private var observeJob: Job? = null
    private var processJob: Job? = null

    /** Start live readings. Idempotent. */
    fun startObserving(intervalMs: Long = 2_000L) {
        if (_state.value.processes.expanded) startProcessLoop()
        if (observeJob?.isActive == true) return
        observeJob = viewModelScope.launch {
            observeRam(intervalMs).collect { snap ->
                _state.update { it.copy(snapshot = snap) }
            }
        }
    }

    /** Stop readings when the UI is hidden, to save battery. */
    fun stopObserving() {
        observeJob?.cancel()
        observeJob = null
        stopProcessLoop()
    }

    /** Opens or closes the list behind the memory hero; it refreshes every few seconds while open and visible. */
    fun toggleProcesses() {
        val open = !_state.value.processes.expanded
        _state.update { it.copy(processes = it.processes.copy(expanded = open)) }
        if (open) startProcessLoop() else stopProcessLoop()
    }

    private fun startProcessLoop() {
        if (processJob?.isActive == true) return
        processJob = viewModelScope.launch {
            while (isActive) {
                if (_state.value.processes.apps.isEmpty()) {
                    _state.update { it.copy(processes = it.processes.copy(loading = true)) }
                }
                val r = topProcesses()
                _state.update {
                    it.copy(processes = it.processes.copy(loading = false, apps = r.apps, message = r.unavailable))
                }
                delay(4_000L)
            }
        }
    }

    private fun stopProcessLoop() {
        processJob?.cancel()
        processJob = null
    }

    fun clear() {
        if (_state.value.isClearing) return
        viewModelScope.launch {
            _state.update { it.copy(isClearing = true, lastClearResult = null) }
            val result = clearMemory()   // runs on IO inside the use case
            _state.update { it.copy(isClearing = false, lastClearResult = result) }
        }
    }
}