package com.example.downloader.ytdlp

import android.content.Context
import com.example.domain.model.DownloadError
import com.example.domain.model.DownloadProgress
import com.example.domain.model.DownloadRequest
import com.example.domain.model.DownloadTask
import com.example.domain.model.FormatInfo
import com.example.domain.model.VideoInfo
import com.example.downloader.engine.DownloadEngine
import com.example.downloader.engine.VideoExtractor
import com.example.downloader.engine.YtDlpDownloadEngine
import com.example.downloader.ffmpeg.FFmpegManager
import com.example.ytdlp.YtDlpEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Implementation of VideoExtractor and DownloadEngine interfaces.
 * Connects the Domain and UI layers to the underlying yt-dlp Python engine and FFmpeg.
 */
class YtDlpEngineBridge(private val context: Context) : VideoExtractor, DownloadEngine {

    private val realDownloadEngine = YtDlpDownloadEngine(
        context = context,
        ffmpegManager = FFmpegManager(context)
    )

    override suspend fun validateUrl(url: String): Boolean = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        (trimmed.startsWith("http://") || trimmed.startsWith("https://")) && trimmed.length > 8
    }

    override suspend fun extractInfo(url: String, processId: String?): Result<VideoInfo> = withContext(Dispatchers.IO) {
        if (!validateUrl(url)) {
            return@withContext Result.failure(
                DownloadError.InvalidUrl("Please enter a valid video URL", "URL must start with http:// or https://")
            )
        }

        val initResult = YtDlpEngine.init(context)
        if (initResult.isFailure) {
            return@withContext Result.failure(
                DownloadError.YtDlpError(
                    msg = "Failed to initialize yt-dlp engine",
                    detail = initResult.exceptionOrNull()?.message
                )
            )
        }

        YtDlpEngine.extractInfo(url, processId, context)
    }

    override suspend fun getFormats(url: String, processId: String?): Result<List<FormatInfo>> = withContext(Dispatchers.IO) {
        extractInfo(url, processId).map { it.formats }
    }

    override suspend fun cancel(taskId: String) {
        withContext(Dispatchers.IO) {
            realDownloadEngine.cancel(taskId)
        }
    }

    override suspend fun download(
        request: DownloadRequest,
        onProgress: (DownloadProgress) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        realDownloadEngine.download(request, onProgress)
    }

    override suspend fun download(
        task: DownloadTask,
        onProgress: (DownloadProgress) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        realDownloadEngine.download(task, onProgress)
    }
}
