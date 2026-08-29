package com.example.domain.util

object FileNameSanitizer {

    private val ILLEGAL_CHARS = Regex("""[\\/:*?"<>|\r\n\t]""")
    private val CONSECUTIVE_DOTS = Regex("""\.+""")

    fun sanitize(rawTitle: String, extension: String = "mp4"): String {
        val cleanExt = extension.trim().removePrefix(".")
        val sanitizedTitle = rawTitle
            .replace(ILLEGAL_CHARS, "_")
            .replace(CONSECUTIVE_DOTS, "_")
            .trim()
            .trim('_')
            .trim('.')

        val finalTitle = if (sanitizedTitle.isBlank()) {
            "video_${System.currentTimeMillis()}"
        } else {
            // Android filename max length is typically 255 bytes. Keep title under 120 chars safely.
            if (sanitizedTitle.length > 120) {
                sanitizedTitle.substring(0, 120).trim()
            } else {
                sanitizedTitle
            }
        }

        return if (cleanExt.isNotBlank()) "$finalTitle.$cleanExt" else finalTitle
    }

    fun generateUniqueFileName(baseName: String): String {
        val dotIndex = baseName.lastIndexOf('.')
        return if (dotIndex > 0) {
            val name = baseName.substring(0, dotIndex)
            val ext = baseName.substring(dotIndex)
            "${name}_${System.currentTimeMillis()}$ext"
        } else {
            "${baseName}_${System.currentTimeMillis()}"
        }
    }
}

