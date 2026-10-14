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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ScanProgress {
    data class Started(val totalScanners: Int) : ScanProgress
    data class ScannerStarted(val name: String, val index: Int, val total: Int) : ScanProgress
    data class ScannerSkipped(val name: String, val reason: String) : ScanProgress
    data class ScannerFailed(val name: String, val reason: String) : ScanProgress
    /** The scanner ran, but its result is incomplete (see [Scanner.incompleteReasons]). Not the same as "found nothing". */
    data class ScannerIncomplete(val name: String, val reasons: List<String>) : ScanProgress
    data class Finished(val totalItems: Int) : ScanProgress
}

@Singleton
class ScanRepository @Inject constructor(
    private val scanners: Set<@JvmSuppressWildcards Scanner>,
    private val capabilities: Capabilities
) {
    private val results = MutableStateFlow<Map<String, List<ScanItem>>>(emptyMap())
    private val _items = MutableStateFlow<List<ScanItem>>(emptyList())
    private val scanMutex = Mutex()

    /** Everything found so far, from every scanner that has run. */
    val items: StateFlow<List<ScanItem>> = _items.asStateFlow()

    private fun publish() {
        _items.value = results.value.values.flatten().sortedWith(
            compareBy<ScanItem> { it.category.ordinal }.thenByDescending { it.sizeBytes }
        )
    }

    fun scan(mode: ScanMode): Flow<ScanProgress> = flow {
        // Single-flight guard: only one scan runs at a time.
        // A second caller waits until the first finishes or is cancelled.
        scanMutex.withLock {
            val selected = scanners.filter { it.mode == mode }
            emit(ScanProgress.Started(selected.size))

            for ((index, scanner) in selected.withIndex()) {
                val missing = missing(scanner)
                if (missing != null) {
                    results.update { it - scanner.id }
                    publish()
                    emit(ScanProgress.ScannerSkipped(scanner.displayName, missing))
                    continue
                }

                emit(ScanProgress.ScannerStarted(scanner.displayName, index + 1, selected.size))

                val found = ArrayList<ScanItem>()
                var reasons: List<String> = emptyList()

                try {
                    scanner.scan().collect { found += it }
                    results.update { it + (scanner.id to found.toList()) }
                    reasons = scanner.incompleteReasons()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    results.update { it - scanner.id }
                    emit(ScanProgress.ScannerFailed(scanner.displayName, t.message ?: t.javaClass.simpleName))
                }

                publish()
                if (reasons.isNotEmpty()) emit(ScanProgress.ScannerIncomplete(scanner.displayName, reasons))
            }

            emit(ScanProgress.Finished(_items.value.size))
        }
    }

    /** Drop items that no longer exist (after cleaning) without rescanning. */
    fun forget(paths: Set<String>) {
        results.update { map -> map.mapValues { (_, list) -> list.filter { it.path !in paths } } }
        publish()
    }

    /** Null if the scanner may run; otherwise the actionable reason it cannot. */
    private suspend fun missing(scanner: Scanner): String? = when (scanner.requirement) {
        Requirement.NONE -> null
        Requirement.ALL_FILES_ACCESS ->
            if (capabilities.hasAllFilesAccess()) null else "needs All-files access"
        Requirement.SHIZUKU -> {
            // Ask per operation: a live Shizuku binder does not prove that listing or measuring works.
            val blocked = scanner.privilegedOperations
                .map { capabilities.readiness(it) }
                .firstOrNull { !it.canAttempt }
            when {
                blocked != null -> blocked.reason
                scanner.privilegedOperations.isEmpty() && !capabilities.shizukuAuthorized() -> "needs Shizuku"
                else -> null
            }
        }
    }
}