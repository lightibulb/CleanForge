package com.cleanforge.data

enum class Risk { SAFE, ELEVATED, DANGEROUS }
enum class Confidence { HIGH, MEDIUM, LOW }
enum class Reversibility { REVERSIBLE, NOT_REVERSIBLE }

data class ScanItem(
    val category: String,
    val displayName: String,
    val path: String,
    val reason: String,
    val sizeBytes: Long,
    val risk: Risk,
    val confidence: Confidence,
    val reversibility: Reversibility,
    val requiresShizuku: Boolean,
)

object SampleData {
    val QUICK = listOf(
        ScanItem("App caches", "WhatsApp cache", "/storage/emulated/0/Android/data/com.whatsapp/cache",
            "Package installed + exact path match", 245_000_000L, Risk.SAFE, Confidence.HIGH, Reversibility.REVERSIBLE, true),
        ScanItem("App caches", "Instagram cache", "/storage/emulated/0/Android/data/com.instagram.android/cache",
            "Package installed + exact path match", 180_000_000L, Risk.SAFE, Confidence.HIGH, Reversibility.REVERSIBLE, true),
        ScanItem("Leftovers", "com.oldgame.app", "/storage/emulated/0/Android/data/com.oldgame.app",
            "Package unknown to OS + age ≥ 14 days", 520_000_000L, Risk.SAFE, Confidence.MEDIUM, Reversibility.NOT_REVERSIBLE, true),
        ScanItem("Thumbnails", ".thumbnails", "/storage/emulated/0/DCIM/.thumbnails",
            "Only small files, no sub-folders", 45_000_000L, Risk.SAFE, Confidence.HIGH, Reversibility.REVERSIBLE, false),
        ScanItem("Incomplete downloads", "video.mp4.crdownload", "/storage/emulated/0/Download/video.mp4.crdownload",
            "Age ≥ 7 days + Download folder only", 1_200_000_000L, Risk.SAFE, Confidence.HIGH, Reversibility.NOT_REVERSIBLE, false),
        ScanItem("Log files", "app.log", "/storage/emulated/0/Download/app.log",
            "Text content verified + age ≥ 30 days", 12_000_000L, Risk.SAFE, Confidence.MEDIUM, Reversibility.NOT_REVERSIBLE, false),
        ScanItem("APK installers", "old_app.apk", "/storage/emulated/0/Download/old_app.apk",
            "Platform parser reads manifest", 85_000_000L, Risk.SAFE, Confidence.HIGH, Reversibility.NOT_REVERSIBLE, false),
        ScanItem("Empty folders", "empty_folder", "/storage/emulated/0/Download/empty_folder",
            "Verifiably empty directory", 0L, Risk.SAFE, Confidence.HIGH, Reversibility.NOT_REVERSIBLE, false),
    )

    val ON_DEMAND = listOf(
        ScanItem("Large files", "movie.mp4", "/storage/emulated/0/Movies/movie.mp4",
            "File over threshold (2.1 GB)", 2_100_000_000L, Risk.ELEVATED, Confidence.LOW, Reversibility.NOT_REVERSIBLE, false),
        ScanItem("Duplicates", "IMG_20240101_copy.jpg", "/storage/emulated/0/DCIM/Camera/IMG_20240101_copy.jpg",
            "Same content as IMG_20240101.jpg", 4_500_000L, Risk.ELEVATED, Confidence.HIGH, Reversibility.NOT_REVERSIBLE, false),
        ScanItem("Large files", "backup.zip", "/storage/emulated/0/Download/backup.zip",
            "File over threshold (1.8 GB)", 1_800_000_000L, Risk.ELEVATED, Confidence.LOW, Reversibility.NOT_REVERSIBLE, false),
    )
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val k = 1024.0
    val sizes = arrayOf("B", "KB", "MB", "GB", "TB")
    val i = (kotlin.math.log(bytes.toDouble()) / kotlin.math.log(k)).toInt().coerceIn(0, 4)
    val v = bytes / Math.pow(k, i.toDouble())
    return String.format("%.1f %s", v, sizes[i])
}
