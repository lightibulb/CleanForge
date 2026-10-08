package com.cleanforge.data.repository

import com.cleanforge.core.backend.Capabilities
import com.cleanforge.core.model.ScanItem
import com.cleanforge.core.scanner.Requirement
import com.cleanforge.core.scanner.ScanMode
import com.cleanforge.core.scanner.Scanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ScanProgress {
    data class Started(val totalScanners: Int) : ScanProgress
    data class ScannerStarted(val name: String, val index: Int, val total: Int) : ScanProgress
    data class ScannerSkipped(val name: String, val reason: String) : ScanProgress
    data class ScannerFailed(val name: String, val reason: String) : ScanProgress
    data class Finished(val totalItems: Int) : ScanProgress
}

@Singleton
class ScanRepository @Inject constructor(
    private val scanners: Set<@JvmSuppressWildcards Scanner>,
    private val capabilities: Capabilities
) {
    private val results = MutableStateFlow<Map<String, List<ScanItem>>>(emptyMap())
    private val _items = MutableStateFlow<List<ScanItem>>(emptyList())

    /** Everything found so far, from every scanner that has run. */
    val items: StateFlow<List<ScanItem>> = _items.asStateFlow()

    private fun publish() {
        _items.value = results.value.values.flatten().sortedWith(
            compareBy<ScanItem> { it.category.ordinal }.thenByDescending { it.sizeBytes }
        )
    }

    fun scan(mode: ScanMode): Flow<ScanProgress> = flow {
        val selected = scanners.filter { it.mode == mode }
        emit(ScanProgress.Started(selected.size))
        for ((index, scanner) in selected.withIndex()) {
            val missing = missing(scanner.requirement)
            if (missing != null) {
                results.update { it - scanner.id }
                publish()
                emit(ScanProgress.ScannerSkipped(scanner.displayName, missing))
                continue
            }
            emit(ScanProgress.ScannerStarted(scanner.displayName, index + 1, selected.size))
            val found = ArrayList<ScanItem>()
            try {
                scanner.scan().collect { found += it }
                results.update { it + (scanner.id to found.toList()) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                results.update { it - scanner.id }
                emit(ScanProgress.ScannerFailed(scanner.displayName, t.message ?: t.javaClass.simpleName))
            }
            publish()
        }
        emit(ScanProgress.Finished(_items.value.size))
    }

    /** Drop items that no longer exist (after cleaning) without rescanning. */
    fun forget(paths: Set<String>) {
        results.update { map -> map.mapValues { (_, list) -> list.filter { it.path !in paths } } }
        publish()
    }

    private fun missing(req: Requirement): String? = when (req) {
        Requirement.NONE -> null
        Requirement.ALL_FILES_ACCESS ->
            if (capabilities.hasAllFilesAccess()) null else "needs All-files access"
        Requirement.SHIZUKU ->
            if (capabilities.shizukuAuthorized()) null else "needs Shizuku"
    }
}
