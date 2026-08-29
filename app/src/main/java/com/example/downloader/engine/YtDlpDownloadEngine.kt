package com.example.downloader.engine

import android.content.Context
import android.os.StatFs
import com.example.domain.model.CutMode
import com.example.domain.model.DownloadError
import com.example.domain.model.DownloadProgress
import com.example.domain.model.DownloadRequest
import com.example.domain.model.DownloadTask
import com.example.domain.util.FileNameSanitizer
import com.example.downloader.ffmpeg.FFmpegManager
import com.example.storage.MediaStoreHelper
import com.example.ytdlp.YtDlpEngine
import com.example.ytdlp.YtDlpErrorMapper
import com.example.ytdlp.YtDlpLogger
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.regex.Pattern

/**
 * Real implementation of DownloadEngine using embedded yt-dlp and FFmpeg.
 * Operates purely on Dispatchers.IO with real-time speed, progress, ETA tracking,
 * and graceful cancellation.
 */
class YtDlpDownloadEngine(
    private val context: Context,
    private val ffmpegManager: FFmpegManager? = null
) : DownloadEngine {

    private val speedPattern = Pattern.compile("""(\d+(?:\.\d+)?\s*(?:[kKMGT]?i?[bB])/s)""")
    private val sizePattern = Pattern.compile("""(\d+(?:\.\d+)?\s*(?:[kKMGT]?i?[bB]))\s*of\s*(?:~?\s*)(\d+(?:\.\d+)?\s*(?:[kKMGT]?i?[bB]))""")

    init {
        YtDlpEngine.init(context)
    }

    override suspend fun download(
        request: DownloadRequest,
        onProgress: (DownloadProgress) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val taskId = request.id
        val startTimeMs = System.currentTimeMillis()

        // 1. Check storage space before proceeding (require at least 50MB)
        if (!hasAvailableStorage(context, 50 * 1024 * 1024L)) {
            val storageError = DownloadError.StorageError(
                msg = "There is not enough storage space on the device to start this download.",
                detail = "Available cache storage is below the minimum safety threshold (50MB)."
            )
            return@withContext Result.failure(storageError)
        }

        // 2. Prepare isolated working directory for this task
        val workDir = File(MediaStoreHelper.getTempDownloadDir(context), taskId)
        if (!workDir.exists()) {
            workDir.mkdirs()
        }

        YtDlpLogger.logDownloadStarted(taskId, request.url, request.resolveFormatSelector())

        try {
            val ytdlRequest = buildYoutubeDLRequest(workDir, request)

            YoutubeDL.getInstance().execute(ytdlRequest, taskId) { progress, etaInSeconds, line ->
                val rawLine = line.orEmpty()
                val speed = extractSpeed(rawLine)
                val etaFormatted = if (etaInSeconds > 0) {
                    val minutes = etaInSeconds / 60
                    val seconds = etaInSeconds % 60
                    String.format("%02d:%02d", minutes, seconds)
                } else ""

                val (downloadedBytesStr, totalBytesStr) = extractSizes(rawLine)

                val progressObj = DownloadProgress(
                    taskId = taskId,
                    progressPercentage = progress.coerceIn(0f, 100f),
                    speed = speed,
                    eta = etaFormatted,
                    statusText = if (downloadedBytesStr.isNotBlank() && totalBytesStr.isNotBlank()) {
                        "$downloadedBytesStr / $totalBytesStr"
                    } else ""
                )
                onProgress(progressObj)
            }

            // 3. Locate final completed file
            val downloadedFiles = workDir.listFiles()?.filter {
                it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl")
            }

            val finalFile = downloadedFiles?.maxByOrNull { it.lastModified() }
            if (finalFile == null || !finalFile.exists() || finalFile.length() == 0L) {
                val fileError = DownloadError.Generic(
                    msg = "Download finished, but output file was not found or is empty.",
                    detail = "Directory ${workDir.absolutePath} contains no valid media files."
                )
                cleanupWorkDir(workDir)
                return@withContext Result.failure(fileError)
            }

            YtDlpLogger.logDownloadCompleted(
                taskId = taskId,
                outputFile = finalFile.absolutePath,
                fileSizeBytes = finalFile.length(),
                durationMs = System.currentTimeMillis() - startTimeMs
            )

            Result.success(finalFile)
        } catch (e: YoutubeDLException) {
            cleanupWorkDir(workDir)
            val domainError = YtDlpErrorMapper.map(e)
            YtDlpLogger.logDownloadError(taskId, domainError, System.currentTimeMillis() - startTimeMs)
            Result.failure(domainError)
        } catch (e: Throwable) {
            cleanupWorkDir(workDir)
            val domainError = YtDlpErrorMapper.map(e)
            YtDlpLogger.logDownloadError(taskId, domainError, System.currentTimeMillis() - startTimeMs)
            Result.failure(domainError)
        }
    }

    override suspend fun download(
        task: DownloadTask,
        onProgress: (DownloadProgress) -> Unit
    ): Result<File> {
        val request = DownloadRequest(
            id = task.id,
            url = task.url,
            formatSelector = task.formatId,
            startTime = task.cutSettings.startTime,
            endTime = task.cutSettings.endTime,
            cutMode = task.cutSettings.mode,
            title = task.title,
            thumbnailUrl = task.thumbnailUrl,
            formatDescription = task.formatDescription,
            isAudioOnly = task.formatDescription.contains("Audio", ignoreCase = true)
        )
        return download(request, onProgress)
    }

    override suspend fun cancel(taskId: String) {
        withContext(Dispatchers.IO) {
            try {
                YtDlpLogger.logDownloadCancelled(taskId, 0L)
                YoutubeDL.getInstance().destroyProcessById(taskId)
                ffmpegManager?.cancel(taskId)

                val workDir = File(MediaStoreHelper.getTempDownloadDir(context), taskId)
                cleanupWorkDir(workDir)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun buildYoutubeDLRequest(workDir: File, request: DownloadRequest): YoutubeDLRequest {
        val outputPattern = "${workDir.absolutePath}/%(title)s.%(ext)s"
        val req = YoutubeDLRequest(request.url.trim())

        req.addOption("-o", outputPattern)
        req.addOption("-c") // continue partially downloaded files
        req.addOption("--no-playlist")
        req.addOption("--no-mtime")
        req.addOption("--concurrent-fragments", "4")
        req.addOption("--no-warnings")
        req.addOption("--socket-timeout", "30")
        req.addOption("--geo-bypass")
        req.addOption("--no-check-certificates")
        req.addOption("--retries", "10")
        req.addOption("--fragment-retries", "10")
        req.addOption("--retry-sleep", "1")
        req.addOption("--extractor-args", "youtube:player_client=android,web,ios")

        // Provide embedded FFmpeg location to yt-dlp for muxing
        try {
            val ffmpegBinary = ffmpegManager?.getFFmpegBinary() ?: com.example.downloader.ffmpeg.FFmpegManager(context).getFFmpegBinary()
            if (ffmpegBinary != null && ffmpegBinary.exists()) {
                val ffmpegDir = ffmpegBinary.parentFile?.absolutePath ?: ffmpegBinary.absolutePath
                req.addOption("--ffmpeg-location", ffmpegDir)
            }
        } catch (_: Throwable) {}

        // Apply Cookies if configured
        try {
            val cookiesContent = com.example.data.settings.AppSettings.getInstance(context).cookiesContent.value
            if (cookiesContent.isNotBlank()) {
                val cookiesFile = File(context.cacheDir, "yt_cookies.txt")
                cookiesFile.writeText(cookiesContent)
                req.addOption("--cookies", cookiesFile.absolutePath)
            }
        } catch (_: Throwable) {}

        // Subtitles handling
        if (request.downloadSubtitles) {
            req.addOption("--write-subs")
            req.addOption("--write-auto-subs")
            val lang = request.subtitleLanguage?.ifBlank { "ar,en" } ?: "ar,en"
            req.addOption("--sub-lang", lang)
            if (!request.isAudioOnly) {
                req.addOption("--embed-subs")
            }
        }

        // Format selection
        val formatSelector = request.resolveFormatSelector()
        if (request.isAudioOnly) {
            req.addOption("-f", formatSelector)
            req.addOption("-x")
            req.addOption("--audio-format", "mp3")
        } else {
            req.addOption("-f", formatSelector)
            req.addOption("--merge-output-format", "mp4")
        }

        // Cutting / Trimming sections
        if (request.hasTimeTrim) {
            val start = request.startTime!!.trim()
            val end = request.endTime!!.trim()
            req.addOption("--download-sections", "*$start-$end")

            if (request.cutMode == CutMode.PRECISE_CUT) {
                req.addOption("--force-keyframes-at-cuts")
            }
        }

        return req
    }

    private fun extractSpeed(line: String): String {
        val matcher = speedPattern.matcher(line)
        return if (matcher.find()) {
            matcher.group(1).orEmpty()
        } else ""
    }

    private fun extractSizes(line: String): Pair<String, String> {
        val matcher = sizePattern.matcher(line)
        return if (matcher.find()) {
            Pair(matcher.group(1).orEmpty(), matcher.group(2).orEmpty())
        } else Pair("", "")
    }

    private fun cleanupWorkDir(workDir: File) {
        try {
            workDir.deleteRecursively()
        } catch (_: Exception) {}
    }

    private fun hasAvailableStorage(context: Context, requiredBytes: Long): Boolean {
        return try {
            val stat = StatFs(context.cacheDir.path)
            val available = stat.availableBlocksLong * stat.blockSizeLong
            available >= requiredBytes
        } catch (e: Exception) {
            true
        }
    }
}
