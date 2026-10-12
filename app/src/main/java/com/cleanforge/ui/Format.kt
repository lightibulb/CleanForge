package com.cleanforge.ui

import com.cleanforge.core.model.ScanItem
import com.cleanforge.core.model.SizeQuality
import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble() / 1024
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return String.format(Locale.getDefault(), "%.1f %s", v, units[i])
}

/** Size text for a scan item: "at least" when the measurement was cut short or part of the folder was unreadable. */
fun ScanItem.sizeLabel(): String {
    val base = formatBytes(if (sizeBytes > 0) sizeBytes else 0L)
    return if (sizeQuality == SizeQuality.LOWER_BOUND) "≥ $base" else base
}
