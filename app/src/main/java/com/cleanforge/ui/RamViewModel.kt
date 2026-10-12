package com.cleanforge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleanforge.core.memory.ClearMemoryResult
import com.cleanforge.core.memory.RamSnapshot
import com.cleanforge.domain.usecase.ClearMemoryUseCase
import com.cleanforge.domain.usecase.ObserveRamUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RamUiState(
    val snapshot: RamSnapshot? = null,
    /** The last reading failed: memory is "unknown", not "still loading". */
    val readFailed: Boolean = false,
    val isClearing: Boolean = false,
    val lastClearResult: ClearMemoryResult? = null
)

@HiltViewModel
class RamViewModel @Inject constructor(
    private val observeRam: ObserveRamUseCase,
    private val clearMemory: ClearMemoryUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(RamUiState())
    val state: StateFlow<RamUiState> = _state.asStateFlow()

    private var observeJob: Job? = null

    /** Start live readings. Idempotent. */
    fun startObserving(intervalMs: Long = 2_000L) {
        if (observeJob?.isActive == true) return
        observeJob = viewModelScope.launch {
            observeRam(intervalMs).collect { snap ->
                _state.update { it.copy(snapshot = snap, readFailed = snap == null) }
            }
        }
    }

    /** Stop readings when the UI is hidden, to save battery. */
    fun stopObserving() {
        observeJob?.cancel()
        observeJob = null
    }

    fun clear() {
        if (_state.value.isClearing) return
        viewModelScope.launch {
            _state.update { it.copy(isClearing = true, lastClearResult = null) }
            try {
                val result = clearMemory()   // runs on IO inside the use case
                _state.update { it.copy(lastClearResult = result) }
            } finally {
                // Also on cancellation or failure: the button must never stay disabled forever.
                _state.update { it.copy(isClearing = false) }
            }
        }
    }
}
