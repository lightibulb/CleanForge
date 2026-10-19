package com.cleanforge.domain.usecase

import android.content.Context
import android.content.pm.ApplicationInfo
import android.provider.MediaStore
import com.cleanforge.core.model.CleaningCategory
import com.cleanforge.core.model.ScanItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class StorageSlice(val label: String, val bytes: Long)

data class StorageCategory(
    val key: String,
    val label: String,
    val bytes: Long,
    /** An honest caveat about what this number does and does not include. */
    val note: String?,
    val slices: List<StorageSlice>
)

data class StorageBreakdown(val categories: List<StorageCategory>, val otherBytes: Long)

/**
 * Where the used storage goes. Photos, videos, audio and documents come from Android's media index (needs All-files
 * access); apps are measured by their installed code (Android hides each app's private data from other apps); caches
 * come from the last scan. Whatever these cannot see is reported as "System and other", never hidden.
 */
class StorageBreakdownUseCase @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val labels = HashMap<String, String>()

    suspend operator fun invoke(usedBytes: Long, scanItems: List<ScanItem>): StorageBreakdown =
        withContext(Dispatchers.IO) {
            val categories = readMedia() + readApps() + junkFrom(scanItems)
            val seen = categories.sumOf { it.bytes }
            StorageBreakdown(categories, (usedBytes - seen).coerceAtLeast(0L))
        }

    // ---------------------------------------------------------------- media (images, videos, audio, other files)
    private fun readMedia(): List<StorageCategory> {
        val names = listOf("Images", "Videos", "Audio", "Documents and other files")
        val totals = LongArray(4)
        val folders = List(4) { HashMap<String, Long>() }
        var failure: String? = null
        try {
            val files = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.Files.FileColumns.RELATIVE_PATH
            )
            context.contentResolver.query(files, projection, null, null, null)?.use { c ->
                val iSize = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val iType = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                val iPath = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)
                while (c.moveToNext()) {
                    val size = c.getLong(iSize)
                    if (size <= 0L) continue
                    val bucket = when (c.getInt(iType)) {
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> 0
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> 1
                        MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> 2
                        else -> 3
                    }
                    totals[bucket] += size
                    folders[bucket].merge(folderLabel(c.getString(iPath)), size, Long::plus)
                }
            }
        } catch (e: Exception) {
            failure = "Android would not share its media index. Allow All-files access to see this."
        }
        return names.indices.map { i ->
            StorageCategory(
                key = "media$i",
                label = names[i],
                bytes = totals[i],
                note = failure ?: if (i == 3) "Only files Android has indexed; folders it skips are counted under System and other." else null,
                slices = topSlices(folders[i], 6)
            )
        }
    }

    private fun folderLabel(relativePath: String?): String {
        val parts = relativePath.orEmpty().trim('/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "Storage root"
        if (parts[0].equals("Android", ignoreCase = true) && parts.size >= 3) return appLabel(parts[2]) + " (app media)"
        return parts[0]
    }

    // ---------------------------------------------------------------- installed apps
    @Suppress("DEPRECATION")
    private fun readApps(): StorageCategory {
        val pm = context.packageManager
        val sizes = HashMap<String, Long>()
        for (ai in pm.getInstalledApplications(0)) {
            if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            val base = ai.sourceDir ?: continue
            var size = File(base).length()
            ai.splitSourceDirs?.forEach { size += File(it).length() }
            sizes[pm.getApplicationLabel(ai).toString()] = size
        }
        return StorageCategory(
            key = "apps",
            label = "Apps you installed",
            bytes = sizes.values.sum(),
            note = "Installed app code only. Android hides each app's private data and downloads from other apps.",
            slices = topSlices(sizes, 8)
        )
    }

    // ---------------------------------------------------------------- caches and junk from the last scan
    private fun junkFrom(items: List<ScanItem>): StorageCategory {
        val junk = items.filter {
            it.category.isQuick || it.category == CleaningCategory.LOG_FILE || it.category == CleaningCategory.TEMP_FILE
        }
        val byCategory = HashMap<String, Long>()
        for (i in junk) byCategory.merge(i.category.label, i.sizeBytes, Long::plus)
        return StorageCategory(
            key = "junk",
            label = "Caches and junk",
            bytes = byCategory.values.sum(),
            note = if (items.isEmpty()) "Run a scan on the Clean tab to measure this." else "From your last scan.",
            slices = topSlices(byCategory, 6)
        )
    }

    // ---------------------------------------------------------------- helpers
    private fun topSlices(map: Map<String, Long>, n: Int): List<StorageSlice> {
        val sorted = map.entries.filter { it.value > 0 }.sortedByDescending { it.value }
        val top = sorted.take(n).map { StorageSlice(it.key, it.value) }
        val rest = sorted.drop(n).sumOf { it.value }
        return if (rest > 0) top + StorageSlice("Everything else", rest) else top
    }

    @Suppress("DEPRECATION")
    private fun appLabel(pkg: String): String = labels.getOrPut(pkg) {
        try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }
}
