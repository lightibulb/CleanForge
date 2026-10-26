package com.cleanforge.core.inventory

object TypeClassifier {
    private val IMAGE = setOf("jpg","jpeg","png","gif","webp","bmp","heic","heif","avif","dng","raw","svg")
    private val VIDEO = setOf("mp4","mkv","webm","mov","avi","flv","m4v","3gp","ts","mts","wmv")
    private val AUDIO = setOf("mp3","m4a","aac","flac","wav","ogg","opus","wma","amr","mid","midi")
    private val DOCUMENT = setOf("pdf","doc","docx","xls","xlsx","ppt","pptx","txt","md","rtf","odt","ods","epub","csv")
    private val APK = setOf("apk","apks","xapk")
    private val ARCHIVE = setOf("zip","rar","7z","tar","gz","bz2","xz","iso","img")

    fun classify(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in IMAGE -> "image"
            in VIDEO -> "video"
            in AUDIO -> "audio"
            in DOCUMENT -> "document"
            in APK -> "apk"
            in ARCHIVE -> "archive"
            else -> "other"
        }
    }

    fun label(key: String): String = when (key) {
        "image" -> "Images"
        "video" -> "Videos"
        "audio" -> "Audio"
        "document" -> "Documents"
        "apk" -> "Installers"
        "archive" -> "Archives"
        else -> "Other files"
    }
}
