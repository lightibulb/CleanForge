package com.cleanforge.domain.usecase

import com.cleanforge.core.scanner.ScanMode
import com.cleanforge.data.repository.ScanProgress
import com.cleanforge.data.repository.ScanRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ScanDeviceUseCase @Inject constructor(private val repository: ScanRepository) {
    operator fun invoke(mode: ScanMode): Flow<ScanProgress> = repository.scan(mode)
}
