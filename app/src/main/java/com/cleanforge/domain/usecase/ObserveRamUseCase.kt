package com.cleanforge.domain.usecase

import com.cleanforge.core.memory.MemoryInfoProvider
import com.cleanforge.core.memory.RamSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import javax.inject.Inject

/** Emits live RAM snapshots every [intervalMs] while collected; stops on cancellation. */
class ObserveRamUseCase @Inject constructor(
    private val provider: MemoryInfoProvider
) {
    operator fun invoke(intervalMs: Long = 2_000L): Flow<RamSnapshot> = flow {
        while (currentCoroutineContext().isActive) {
            provider.snapshot()?.let { emit(it) }
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.IO)
}