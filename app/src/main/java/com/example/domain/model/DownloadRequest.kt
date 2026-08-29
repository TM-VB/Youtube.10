package com.example.domain.model

import java.util.UUID

/**
 * Domain model representing a structured download request.
 * Encapsulates the target URL, format specification, cut parameters, and output destination.
 */
data class DownloadRequest(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val formatSelector: String = "bestvideo+bestaudio/best",
    val startTime: String? = null,
    val endTime: String? = null,
    val cutMode: CutMode = CutMode.FAST_CUT,
    val outputName: String? = null,
    val outputDestination: String? = null,
    val isAudioOnly: Boolean = false,
    val isVideoOnly: Boolean = false,
    val title: String = "Video",
    val thumbnailUrl: String? = null,
    val formatDescription: String = "Best Quality"
) {
    val isFullVideo: Boolean
        get() = startTime.isNullOrBlank() || endTime.isNullOrBlank()

    val hasTimeTrim: Boolean
        get() = !isFullVideo

    /**
     * Resolves the final yt-dlp format parameter string.
     * Ensures video-only formats (such as 137, 248) are merged with bestaudio
     * so that the resulting video is not silent.
     */
    fun resolveFormatSelector(): String {
        val trimmed = formatSelector.trim()
        return when {
            isAudioOnly -> {
                if (trimmed.isNotBlank() && trimmed != "best") trimmed else "bestaudio/best"
            }
            trimmed.contains("+") || trimmed.contains("/") || trimmed.equals("best", ignoreCase = true) -> {
                trimmed
            }
            isVideoOnly || trimmed.all { it.isDigit() } -> {
                // If it's a numeric format ID for video only, combine with bestaudio
                "$trimmed+bestaudio/best"
            }
            else -> {
                if (trimmed.isNotBlank()) "$trimmed+bestaudio/best" else "bestvideo+bestaudio/best"
            }
        }
    }
}
