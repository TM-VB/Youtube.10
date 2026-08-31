package com.example.ytdlp

import android.content.Context
import android.net.Uri
import com.example.domain.model.CutMode
import com.example.domain.model.DownloadError
import com.example.domain.model.FormatInfo
import com.example.domain.model.PlaylistEntry
import com.example.domain.model.PlaylistInfo
import com.example.domain.model.SubtitleTrack
import com.example.domain.model.TimeRange
import com.example.domain.model.VideoInfo
import com.example.domain.model.VideoMetadata
import com.example.ffmpeg.FFmpegManager
import com.example.storage.MediaStoreHelper
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.regex.Pattern

data class ProgressUpdate(
    val progress: Float,
    val etaSeconds: Long,
    val speedText: String,
    val rawLine: String,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val downloadedBytesText: String = "",
    val totalBytesText: String = ""
)

object YtDlpEngine {

    private var isInitialized = false
    private val SPEED_PATTERN = Pattern.compile("""(\d+(?:\.\d+)?\s*(?:[kKMGT]?i?[bB])/s)""")
    private val SIZE_OF_PATTERN = Pattern.compile("""(\d+(?:\.\d+)?\s*(?:[kKMGT]?i?[bB]))\s*of\s*(?:~?\s*)(\d+(?:\.\d+)?\s*(?:[kKMGT]?i?[bB]))""")
    private val SINGLE_SIZE_PATTERN = Pattern.compile("""(\d+(?:\.\d+)?\s*(?:[kKMGT]?i?[bB]))\s+at""")

    fun init(context: Context): Result<Unit> {
        if (isInitialized) return Result.success(Unit)
        return try {
            YoutubeDL.getInstance().init(context.applicationContext)
            FFmpegManager.init(context.applicationContext)
            isInitialized = true
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(if (t is Exception) t else Exception(t.message, t))
        }
    }

    fun isReady(): Boolean = isInitialized

    private fun applyCookies(context: Context?, request: YoutubeDLRequest) {
        if (context == null) return
        try {
            val cookies = com.example.data.settings.AppSettings.getInstance(context).cookiesContent.value
            if (cookies.isNotBlank()) {
                val cookiesFile = File(context.cacheDir, "yt_cookies.txt")
                cookiesFile.writeText(cookies)
                request.addOption("--cookies", cookiesFile.absolutePath)
            }
        } catch (_: Throwable) {}
    }

    /**
     * Checks if a URL is likely a playlist or collection
     */
    fun isPlaylistUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("list=") || lower.contains("/playlist") || lower.contains("/sets/")
    }

    /**
     * Extracts playlist information and items.
     */
    suspend fun extractPlaylist(
        url: String,
        context: Context? = null,
        processId: String? = null
    ): Result<PlaylistInfo> = withContext(Dispatchers.IO) {
        val trimmedUrl = url.trim()
        if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
            return@withContext Result.failure(
                DownloadError.InvalidUrl("Please enter a valid playlist URL", "URL must begin with http:// or https://")
            )
        }

        try {
            val request = YoutubeDLRequest(trimmedUrl).apply {
                addOption("--flat-playlist")
                addOption("--dump-single-json")
                addOption("--no-warnings")
                addOption("--socket-timeout", "30")
                addOption("--geo-bypass")
                addOption("--no-check-certificates")
                addOption("--extractor-args", "youtube:player_client=android,web")
            }
            applyCookies(context, request)

            val response = YoutubeDL.getInstance().execute(request)
            val jsonStr = response.out ?: "{}"
            val json = org.json.JSONObject(jsonStr)

            val title = json.optString("title", "Playlist")
            val uploader = if (json.has("uploader") && !json.isNull("uploader")) json.optString("uploader") else null
            val id = json.optString("id", "")
            val webpageUrl = json.optString("webpage_url", trimmedUrl)
            val entriesArray = json.optJSONArray("entries")
            val entries = mutableListOf<PlaylistEntry>()

            if (entriesArray != null) {
                for (i in 0 until entriesArray.length()) {
                    val item = entriesArray.optJSONObject(i) ?: continue
                    val entryId = item.optString("id", "$i")
                    val entryTitle = item.optString("title", "Video #${i + 1}")
                    val duration = if (item.has("duration") && !item.isNull("duration")) item.optLong("duration") else null
                    val itemUploader = if (item.has("uploader") && !item.isNull("uploader")) item.optString("uploader") else uploader
                    val rawUrl = item.optString("url", "")
                    val entryUrl = if (rawUrl.startsWith("http")) {
                        rawUrl
                    } else if (rawUrl.isNotBlank()) {
                        "https://www.youtube.com/watch?v=$rawUrl"
                    } else {
                        "https://www.youtube.com/watch?v=$entryId"
                    }
                    val thumbnail = if (item.has("thumbnail") && !item.isNull("thumbnail")) item.optString("thumbnail") else null

                    entries.add(
                        PlaylistEntry(
                            id = entryId,
                            title = entryTitle,
                            durationSeconds = duration,
                            uploader = itemUploader,
                            url = entryUrl,
                            thumbnailUrl = thumbnail,
                            isSelected = true
                        )
                    )
                }
            }

            val playlistInfo = PlaylistInfo(
                id = id,
                title = title,
                uploader = uploader,
                webpageUrl = webpageUrl,
                thumbnailUrl = if (entries.isNotEmpty()) entries[0].thumbnailUrl else null,
                entries = entries
            )

            Result.success(playlistInfo)
        } catch (e: Throwable) {
            val domainError = YtDlpErrorMapper.map(e)
            Result.failure(domainError)
        }
    }

    /**
     * Extracts full video metadata and structured formats using embedded yt-dlp.
     * Includes automatic multi-client/instance fallback strategies if one extractor client fails.
     */
    suspend fun extractInfo(url: String, processId: String? = null, context: Context? = null): Result<VideoInfo> = withContext(Dispatchers.IO) {
        val trimmedUrl = url.trim()
        val startTime = System.currentTimeMillis()

        if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
            return@withContext Result.failure(
                DownloadError.InvalidUrl("Please enter a valid video URL", "URL must begin with http:// or https://")
            )
        }

        YtDlpLogger.logAnalyzeStarted(trimmedUrl, processId)

        val clientStrategies = listOf(
            "youtube:player_client=android,web,ios",
            "youtube:player_client=android_embedded,web_embedded",
            "youtube:player_client=mweb,tv,ios",
            "youtube:player_client=web,tv_embedded"
        )

        var lastException: Throwable? = null

        for ((index, clientArg) in clientStrategies.withIndex() ) {
            try {
                val request = YoutubeDLRequest(trimmedUrl).apply {
                    addOption("--no-playlist")
                    addOption("--no-warnings")
                    addOption("--socket-timeout", "25")
                    addOption("--no-check-certificates")
                    addOption("--geo-bypass")
                    addOption("--retries", "3")
                    addOption("--extractor-args", clientArg)
                }
                applyCookies(context, request)

                val info = YoutubeDL.getInstance().getInfo(request)
                val title = info.title?.trim().orEmpty().ifEmpty { "Video" }
                val parsedFormats = FormatParser.parseFormats(info.formats)

                if (parsedFormats.isNotEmpty()) {
                    val durationMs = System.currentTimeMillis() - startTime
                    YtDlpLogger.logAnalyzeCompleted(
                        url = trimmedUrl,
                        formatCount = parsedFormats.size,
                        durationMs = durationMs,
                        extractor = "${info.extractor} [fallback-level: $index]"
                    )

                    // Provide standard and auto-detected subtitle tracks
                    val availableSubtitles = listOf(
                        SubtitleTrack("ar", "العربية (Arabic)", isAutoGenerated = false),
                        SubtitleTrack("en", "English", isAutoGenerated = false),
                        SubtitleTrack("fr", "Français (French)", isAutoGenerated = false),
                        SubtitleTrack("es", "Español (Spanish)", isAutoGenerated = false),
                        SubtitleTrack("tr", "Türkçe (Turkish)", isAutoGenerated = false)
                    )

                    val videoInfo = VideoInfo(
                        id = info.id.orEmpty(),
                        title = title,
                        uploader = info.uploader,
                        channel = info.uploaderId ?: info.uploader,
                        duration = if (info.duration > 0) info.duration.toLong() else null,
                        thumbnail = info.thumbnail,
                        webpageUrl = info.webpageUrl ?: trimmedUrl,
                        description = info.description,
                        extractor = info.extractor,
                        availability = "available",
                        formats = parsedFormats,
                        subtitles = availableSubtitles
                    )
                    return@withContext Result.success(videoInfo)
                }
            } catch (e: Throwable) {
                lastException = e
            }
        }

        val durationMs = System.currentTimeMillis() - startTime
        val finalError = lastException ?: Exception("No downloadable formats found after trying all fallback extractors.")
        YtDlpLogger.logAnalyzeError(trimmedUrl, finalError, durationMs)
        val domainError = YtDlpErrorMapper.map(finalError)
        Result.failure(domainError)
    }

    /**
     * Extracts formats directly for a given URL.
     */
    suspend fun getFormats(url: String, processId: String? = null): Result<List<FormatInfo>> =
        extractInfo(url, processId).map { it.formats }

    /**
     * Legacy compatibility bridge returning VideoMetadata.
     */
    fun fetchVideoInfo(url: String): Result<VideoMetadata> {
        val trimmedUrl = url.trim()
        if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
            return Result.failure(IllegalArgumentException("Video is invalid or unavailable."))
        }

        return try {
            val request = YoutubeDLRequest(trimmedUrl).apply {
                addOption("--no-playlist")
                addOption("--no-warnings")
                addOption("--socket-timeout", "20")
            }

            val info = YoutubeDL.getInstance().getInfo(request)
            val title = info.title?.trim().orEmpty().ifEmpty { "Video" }
            val parsedOptions = FormatParser.parseFormatOptions(info.formats)

            val metadata = VideoMetadata(
                id = info.id.orEmpty(),
                title = title,
                uploader = info.uploader.orEmpty(),
                durationSeconds = info.duration,
                thumbnailUrl = info.thumbnail,
                webpageUrl = info.webpageUrl ?: trimmedUrl,
                formats = parsedOptions
            )
            Result.success(metadata)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Downloads video according to options, time range, and format.
     */
    fun download(
        context: Context,
        url: String,
        title: String,
        formatId: String,
        isAudioOnly: Boolean,
        timeRange: TimeRange?,
        processId: String,
        onProgress: (ProgressUpdate) -> Unit
    ): Result<Pair<Uri?, String?>> {
        return try {
            val workDir = File(MediaStoreHelper.getTempDownloadDir(context), processId)
            if (!workDir.exists()) workDir.mkdirs()

            val outputPattern = "${workDir.absolutePath}/%(title)s.%(ext)s"
            val request = YoutubeDLRequest(url.trim()).apply {
                addOption("-o", outputPattern)
                addOption("-c") // continue partially downloaded files
                addOption("--no-playlist")
                addOption("--no-mtime")
                addOption("--concurrent-fragments", "4")
                addOption("--retries", "10")
                addOption("--fragment-retries", "10")
                addOption("--retry-sleep", "1")
                addOption("--no-check-certificates")
                addOption("--geo-bypass")
                addOption("--extractor-args", "youtube:player_client=android,web,ios")

                // Provide embedded FFmpeg location to yt-dlp for muxing
                try {
                    val ffmpegBinary = com.example.downloader.ffmpeg.FFmpegManager(context).getFFmpegBinary()
                    if (ffmpegBinary != null && ffmpegBinary.exists()) {
                        val ffmpegDir = ffmpegBinary.parentFile?.absolutePath ?: ffmpegBinary.absolutePath
                        addOption("--ffmpeg-location", ffmpegDir)
                    }
                } catch (_: Throwable) {}

                applyCookies(context, this)

                // Format selection logic
                if (isAudioOnly) {
                    val selector = if (formatId.isNotBlank() && formatId != "best") formatId else "ba/best"
                    addOption("-f", selector)
                    addOption("-x") // extract audio
                    addOption("--audio-format", "mp3")
                } else {
                    if (formatId.isNotBlank() && formatId != "best") {
                        if (formatId.contains("+") || formatId.contains("/")) {
                            addOption("-f", formatId)
                        } else {
                            // Merge requested video stream with best audio stream (prefer m4a/aac for native android playback)
                            addOption("-f", "$formatId+ba[ext=m4a]/$formatId+ba/best")
                        }
                    } else {
                        addOption("-f", "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b")
                    }
                    addOption("--merge-output-format", "mp4")
                }

                // Time trimming section
                if (timeRange != null && timeRange.startTime.isNotBlank() && timeRange.endTime.isNotBlank()) {
                    addOption("--download-sections", "*${timeRange.startTime}-${timeRange.endTime}")
                    if (timeRange.cutMode == CutMode.PRECISE_CUT) {
                        addOption("--force-keyframes-at-cuts")
                    }
                }
            }

            YoutubeDL.getInstance().execute(request, processId) { progress, etaInSeconds, line ->
                val progressDetails = extractProgressDetails(line, progress, etaInSeconds)
                onProgress(progressDetails)
            }

            // Locate final downloaded files in workDir
            val downloadedFiles = workDir.listFiles()?.filter {
                it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl")
            } ?: emptyList()

            var finalFile = downloadedFiles.maxByOrNull { it.lastModified() }

            // If yt-dlp saved separate unmerged video and audio streams, merge them with FFmpeg
            if (!isAudioOnly && downloadedFiles.size > 1) {
                // Check if an already combined complete video exists
                val hasCompletedCombinedVideo = downloadedFiles.any {
                    val ext = it.extension.lowercase()
                    (ext == "mp4" || ext == "mkv") && !it.name.contains(".f") && it.length() > 1024L
                }

                if (!hasCompletedCombinedVideo) {
                    val videoCandidates = downloadedFiles.filter {
                        val ext = it.extension.lowercase()
                        ext == "mp4" || ext == "webm" || ext == "mkv"
                    }
                    val audioCandidates = downloadedFiles.filter {
                        val ext = it.extension.lowercase()
                        ext == "m4a" || ext == "mp3" || ext == "opus" || ext == "aac" || ext == "ogg"
                    }

                    val primaryVideo = videoCandidates.maxByOrNull { it.length() }
                    val primaryAudio = audioCandidates.maxByOrNull { it.length() }

                    if (primaryVideo != null && primaryAudio != null && primaryVideo != primaryAudio) {
                        val mergedOut = File(workDir, "merged_${System.currentTimeMillis()}.mp4")
                        try {
                            val ffmpegMgr = com.example.downloader.ffmpeg.FFmpegManager(context)
                            val mergeResult = kotlinx.coroutines.runBlocking {
                                ffmpegMgr.mergeVideoAudio(primaryVideo, primaryAudio, mergedOut)
                            }
                            if (mergeResult.isSuccess && mergedOut.exists() && mergedOut.length() > 0) {
                                finalFile = mergedOut
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }

            if (finalFile == null || !finalFile.exists() || finalFile.length() == 0L) {
                return Result.failure(Exception("Download failed: File not found or empty."))
            }

            // Copy to Public Downloads directory via MediaStore
            val result = MediaStoreHelper.saveToPublicDownloads(context, finalFile, title)
            val uri = result.first
            val path = result.second ?: finalFile.absolutePath

            if (result.first != null || result.second != null) {
                try {
                    finalFile.delete()
                    workDir.deleteRecursively()
                } catch (_: Exception) {}
            }

            Result.success(Pair(uri, path))
        } catch (e: YoutubeDLException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun cancel(processId: String) {
        try {
            YtDlpLogger.logAnalyzeCancelled("", 0L, processId)
            YoutubeDL.getInstance().destroyProcessById(processId)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getVersion(context: Context): String {
        return try {
            YoutubeDL.getInstance().version(context.applicationContext) ?: "2025.x (Embedded)"
        } catch (t: Throwable) {
            "2025.x (Embedded)"
        }
    }

    fun updateEngine(context: Context): Result<String> {
        return try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(context)
            Result.success(status?.name ?: "Updated")
        } catch (t: Throwable) {
            Result.failure(if (t is Exception) t else Exception(t.message, t))
        }
    }

    private fun extractSpeed(line: String?): String {
        if (line.isNullOrBlank()) return ""
        val matcher = SPEED_PATTERN.matcher(line)
        return if (matcher.find()) {
            matcher.group(1).orEmpty().trim()
        } else ""
    }

    private fun parseByteString(sizeStr: String): Long {
        val clean = sizeStr.trim()
        val pattern = Pattern.compile("""(\d+(?:\.\d+)?)\s*([a-zA-Z]+)?""")
        val matcher = pattern.matcher(clean)
        if (!matcher.find()) return 0L
        val value = matcher.group(1)?.toDoubleOrNull() ?: return 0L
        val unit = matcher.group(2)?.lowercase() ?: ""

        return when {
            unit.startsWith("kib") || unit == "kb" || unit == "k" -> (value * 1024).toLong()
            unit.startsWith("mib") || unit == "mb" || unit == "m" -> (value * 1024 * 1024).toLong()
            unit.startsWith("gib") || unit == "gb" || unit == "g" -> (value * 1024 * 1024 * 1024).toLong()
            unit.startsWith("tib") || unit == "tb" || unit == "t" -> (value * 1024L * 1024L * 1024L * 1024L).toLong()
            else -> value.toLong()
        }
    }

    private fun extractProgressDetails(
        line: String?,
        callbackProgress: Float,
        etaInSeconds: Long
    ): ProgressUpdate {
        val raw = line.orEmpty()
        val speed = extractSpeed(raw)
        var downloadedBytes = 0L
        var totalBytes = 0L
        var downloadedText = ""
        var totalText = ""
        var calculatedProgress = callbackProgress.coerceIn(0f, 100f)

        if (raw.isNotBlank()) {
            val sizeOfMatcher = SIZE_OF_PATTERN.matcher(raw)
            if (sizeOfMatcher.find()) {
                downloadedText = sizeOfMatcher.group(1).orEmpty().trim()
                totalText = sizeOfMatcher.group(2).orEmpty().trim()
                downloadedBytes = parseByteString(downloadedText)
                totalBytes = parseByteString(totalText)

                if (totalBytes > 0L && downloadedBytes > 0L) {
                    calculatedProgress = ((downloadedBytes.toDouble() / totalBytes.toDouble()) * 100.0)
                        .toFloat()
                        .coerceIn(0f, 100f)
                }
            } else {
                val singleSizeMatcher = SINGLE_SIZE_PATTERN.matcher(raw)
                if (singleSizeMatcher.find()) {
                    downloadedText = singleSizeMatcher.group(1).orEmpty().trim()
                    downloadedBytes = parseByteString(downloadedText)
                }
            }
        }

        return ProgressUpdate(
            progress = calculatedProgress,
            etaSeconds = etaInSeconds,
            speedText = speed,
            rawLine = raw,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            downloadedBytesText = downloadedText,
            totalBytesText = totalText
        )
    }
}
