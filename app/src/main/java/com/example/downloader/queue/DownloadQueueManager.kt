package com.example.downloader.queue

import android.content.Context
import android.net.Uri
import com.example.data.local.AppDatabase
import com.example.data.local.DownloadTaskEntity
import com.example.data.repository.DownloadRepository
import com.example.data.settings.AppSettings
import com.example.domain.model.CutMode
import com.example.domain.model.DownloadError
import com.example.domain.model.DownloadRequest
import com.example.domain.model.DownloadStatus
import com.example.domain.model.TimeRange
import com.example.downloader.cleanup.CleanupManager
import com.example.downloader.engine.DownloadEngine
import com.example.downloader.engine.YtDlpDownloadEngine
import com.example.downloader.ffmpeg.FFmpegManager
import com.example.downloader.network.NetworkMonitor
import com.example.downloader.util.RetryPolicy
import com.example.downloader.util.SpeedSmoother
import com.example.service.DownloadForegroundService
import com.example.storage.MediaStoreHelper
import com.example.ytdlp.ProgressUpdate
import com.example.ytdlp.YtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Advanced Queue & Lifecycle Manager for media downloads.
 * Handles concurrency control, atomic queue reordering, network state transitions,
 * process recovery, exponential backoff retries, speed smoothing, and bulk actions.
 */
class DownloadQueueManager(
    private val context: Context,
    private val repository: DownloadRepository = DownloadRepository(AppDatabase.getInstance(context).downloadTaskDao()),
    private val appSettings: AppSettings = AppSettings.getInstance(context),
    private val networkMonitor: NetworkMonitor = NetworkMonitor(context),
    private val downloadEngine: DownloadEngine = YtDlpDownloadEngine(context, FFmpegManager(context)),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val speedSmoothers = ConcurrentHashMap<String, SpeedSmoother>()
    private val lastProgressUpdateTimes = ConcurrentHashMap<String, Long>()
    private val lastReportedProgress = ConcurrentHashMap<String, Float>()
    private val lastNotificationTimes = ConcurrentHashMap<String, Long>()
    private val queueMutex = Mutex()

    private val _activeDownloadCount = MutableStateFlow(0)
    val activeDownloadCount: StateFlow<Int> = _activeDownloadCount.asStateFlow()

    init {
        // Startup: Recover tasks that were abruptly interrupted by app restart or OS termination
        scope.launch {
            recoverInterruptedDownloads()
            verifyDatabaseConsistency()
        }

        // Network monitoring: Auto-resume queued downloads when connectivity returns
        scope.launch {
            networkMonitor.isOnlineFlow.collect { isOnline ->
                if (isOnline) {
                    processQueue()
                }
            }
        }
    }

    suspend fun recoverInterruptedDownloads() {
        try {
            repository.markActiveTasksAsInterrupted()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun verifyDatabaseConsistency() {
        try {
            val completedTasks = repository.getAllCompletedTasksSync()
            for (task in completedTasks) {
                val path = task.filePath
                val uriStr = task.contentUri
                var exists = false

                if (!path.isNullOrBlank()) {
                    exists = File(path).exists()
                }
                if (!exists && !uriStr.isNullOrBlank()) {
                    try {
                        val uri = Uri.parse(uriStr)
                        context.contentResolver.openInputStream(uri)?.use {
                            exists = true
                        }
                    } catch (_: Exception) {
                        exists = false
                    }
                }

                if (!exists && task.errorMessage == null) {
                    repository.updateTask(
                        task.copy(
                            errorMessage = "File missing from disk or was moved."
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun checkDuplicate(url: String, formatId: String, startTime: String?, endTime: String?): DownloadTaskEntity? {
        return repository.findExistingTask(url, formatId, startTime, endTime)
    }

    fun enqueueDownload(request: DownloadRequest): String {
        val taskId = request.id
        val entity = DownloadTaskEntity(
            id = taskId,
            url = request.url,
            title = request.title,
            thumbnailUrl = request.thumbnailUrl,
            formatId = request.formatSelector,
            formatDescription = request.formatDescription,
            startTime = request.startTime,
            endTime = request.endTime,
            cutMode = request.cutMode.id,
            status = DownloadStatus.QUEUED,
            progress = 0f,
            isAudioOnly = request.isAudioOnly,
            isVideoOnly = request.isVideoOnly,
            downloadSubtitles = request.downloadSubtitles,
            subtitleLanguage = request.subtitleLanguage,
            queueOrder = System.currentTimeMillis()
        )

        scope.launch {
            repository.insertTask(entity)
            processQueue()
        }

        return taskId
    }

    fun enqueueDownload(
        url: String,
        title: String,
        thumbnailUrl: String?,
        formatId: String,
        formatDescription: String,
        isAudioOnly: Boolean,
        timeRange: TimeRange?,
        downloadSubtitles: Boolean = false,
        subtitleLanguage: String? = null
    ): String {
        val taskId = UUID.randomUUID().toString()
        val entity = DownloadTaskEntity(
            id = taskId,
            url = url,
            title = title,
            thumbnailUrl = thumbnailUrl,
            formatId = formatId,
            formatDescription = formatDescription,
            startTime = timeRange?.startTime,
            endTime = timeRange?.endTime,
            cutMode = timeRange?.cutMode?.id ?: "none",
            status = DownloadStatus.QUEUED,
            progress = 0f,
            isAudioOnly = isAudioOnly,
            isVideoOnly = false,
            downloadSubtitles = downloadSubtitles,
            subtitleLanguage = subtitleLanguage,
            queueOrder = System.currentTimeMillis()
        )

        scope.launch {
            repository.insertTask(entity)
            processQueue()
        }

        return taskId
    }

    fun enqueueBatch(requests: List<DownloadRequest>) {
        if (requests.isEmpty()) return
        scope.launch {
            val now = System.currentTimeMillis()
            val entities = requests.mapIndexed { idx, req ->
                DownloadTaskEntity(
                    id = req.id,
                    url = req.url,
                    title = req.title,
                    thumbnailUrl = req.thumbnailUrl,
                    formatId = req.formatSelector,
                    formatDescription = req.formatDescription,
                    startTime = req.startTime,
                    endTime = req.endTime,
                    cutMode = req.cutMode.id,
                    status = DownloadStatus.QUEUED,
                    progress = 0f,
                    isAudioOnly = req.isAudioOnly,
                    isVideoOnly = req.isVideoOnly,
                    downloadSubtitles = req.downloadSubtitles,
                    subtitleLanguage = req.subtitleLanguage,
                    queueOrder = now + idx
                )
            }
            entities.forEach { repository.insertTask(it) }
            processQueue()
        }
    }

    private suspend fun cancelDownloadInternal(taskId: String) {
        try {
            downloadEngine.cancel(taskId)
        } catch (_: Throwable) {}
        try {
            YtDlpEngine.cancel(taskId)
        } catch (_: Throwable) {}
        val job = activeJobs.remove(taskId)
        job?.cancel()
        job?.join()
        speedSmoothers.remove(taskId)
        lastProgressUpdateTimes.remove(taskId)
        lastReportedProgress.remove(taskId)
        lastNotificationTimes.remove(taskId)
        _activeDownloadCount.value = activeJobs.size
        CleanupManager.cleanupTaskFiles(context, taskId)
    }

    fun pauseDownload(taskId: String) {
        scope.launch {
            cancelDownloadInternal(taskId)

            val current = repository.getTaskByIdSync(taskId)
            if (current != null && (current.status == DownloadStatus.DOWNLOADING ||
                        current.status == DownloadStatus.PREPARING ||
                        current.status == DownloadStatus.QUEUED ||
                        current.status == DownloadStatus.PROCESSING_FFMPEG)) {
                repository.updateTask(
                    current.copy(
                        status = DownloadStatus.PAUSED,
                        downloadSpeed = "",
                        eta = ""
                    )
                )
                DownloadForegroundService.updateOrDismissIfIdle(
                    context, taskId, current.title, DownloadStatus.PAUSED, current.progress.toInt(), ""
                )
            }

            processQueue()
        }
    }

    fun resumeDownload(taskId: String) {
        scope.launch {
            val current = repository.getTaskByIdSync(taskId) ?: return@launch
            if (current.status == DownloadStatus.PAUSED || current.status == DownloadStatus.INTERRUPTED) {
                // If temporary download directory has files and we were in processing, attempt direct recovery
                val taskWorkDir = File(context.cacheDir, "ytdlp_downloads/$taskId")
                val files = taskWorkDir.listFiles()?.filter { it.isFile && it.length() > 0 } ?: emptyList()
                val isProcessRecoverable = files.isNotEmpty() && current.status == DownloadStatus.PROCESSING_FFMPEG

                val targetStatus = if (isProcessRecoverable) DownloadStatus.PROCESSING_FFMPEG else DownloadStatus.QUEUED

                repository.updateTask(
                    current.copy(
                        status = targetStatus,
                        errorMessage = null,
                        queueOrder = System.currentTimeMillis()
                    )
                )
                processQueue()
            }
        }
    }

    fun cancelDownload(taskId: String) {
        scope.launch {
            cancelDownloadInternal(taskId)

            val task = repository.getTaskByIdSync(taskId)
            if (task != null && task.status != DownloadStatus.COMPLETED) {
                repository.updateTask(
                    task.copy(
                        status = DownloadStatus.CANCELLED,
                        downloadSpeed = "",
                        eta = ""
                    )
                )
                DownloadForegroundService.updateOrDismissIfIdle(
                    context, taskId, task.title, DownloadStatus.CANCELLED, task.progress.toInt(), ""
                )
            }

            processQueue()
        }
    }

    fun retryDownload(taskId: String) {
        scope.launch {
            val task = repository.getTaskByIdSync(taskId) ?: return@launch

            // Check if source files are intact in task work directory for processing retry
            val taskWorkDir = File(context.cacheDir, "ytdlp_downloads/$taskId")
            val mediaFiles = taskWorkDir.listFiles()?.filter { it.isFile && it.length() > 1024 && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") } ?: emptyList()

            val canResumeProcessing = mediaFiles.isNotEmpty() && task.status == DownloadStatus.PROCESSING_FFMPEG

            val updatedTask = if (canResumeProcessing) {
                task.copy(
                    status = DownloadStatus.PROCESSING_FFMPEG,
                    errorMessage = null,
                    retryCount = task.retryCount + 1,
                    queueOrder = System.currentTimeMillis()
                )
            } else {
                task.copy(
                    status = DownloadStatus.QUEUED,
                    progress = 0f,
                    errorMessage = null,
                    downloadSpeed = "",
                    eta = "",
                    retryCount = task.retryCount + 1,
                    queueOrder = System.currentTimeMillis()
                )
            }

            repository.updateTask(updatedTask)
            processQueue()
        }
    }

    fun deleteDownload(taskId: String) {
        scope.launch {
            cancelDownloadInternal(taskId)
            DownloadForegroundService.updateOrDismissIfIdle(
                context, taskId, "", DownloadStatus.CANCELLED, 0, ""
            )
            repository.deleteTask(taskId)
            processQueue()
        }
    }

    fun reorderTask(taskId: String, newOrder: Long) {
        scope.launch {
            repository.updateQueueOrder(taskId, newOrder)
            processQueue()
        }
    }

    fun moveTaskUp(taskId: String) {
        scope.launch {
            val queued = repository.getQueuedTasks()
            val index = queued.indexOfFirst { it.id == taskId }
            if (index > 0) {
                val currentTask = queued[index]
                val prevTask = queued[index - 1]
                val newOrder = prevTask.queueOrder - 1
                repository.updateQueueOrder(currentTask.id, newOrder)
            }
        }
    }

    fun moveTaskDown(taskId: String) {
        scope.launch {
            val queued = repository.getQueuedTasks()
            val index = queued.indexOfFirst { it.id == taskId }
            if (index >= 0 && index < queued.size - 1) {
                val currentTask = queued[index]
                val nextTask = queued[index + 1]
                val newOrder = nextTask.queueOrder + 1
                repository.updateQueueOrder(currentTask.id, newOrder)
            }
        }
    }

    fun bulkCancel(taskIds: List<String>) {
        taskIds.forEach { cancelDownload(it) }
    }

    fun bulkRetry(taskIds: List<String>) {
        taskIds.forEach { retryDownload(it) }
    }

    fun bulkDelete(taskIds: List<String>) {
        scope.launch {
            for (id in taskIds) {
                cancelDownloadInternal(id)
                DownloadForegroundService.updateOrDismissIfIdle(
                    context, id, "", DownloadStatus.CANCELLED, 0, ""
                )
            }
            repository.deleteTasksByIds(taskIds)
            processQueue()
        }
    }

    fun clearHistory(deletePhysicalFiles: Boolean) {
        scope.launch {
            if (deletePhysicalFiles) {
                val completedTasks = repository.getAllCompletedTasksSync()
                for (task in completedTasks) {
                    task.filePath?.let { path ->
                        try {
                            File(path).delete()
                        } catch (_: Exception) {}
                    }
                    task.contentUri?.let { uriStr ->
                        try {
                            context.contentResolver.delete(Uri.parse(uriStr), null, null)
                        } catch (_: Exception) {}
                    }
                }
            }
            repository.clearFinishedTasks()
        }
    }

    /**
     * Dispatches queued downloads according to concurrency limit.
     */
    suspend fun processQueue() {
        queueMutex.withLock {
            val maxConcurrency = appSettings.concurrentDownloads.value.coerceIn(1, 3)
            val currentActiveCount = activeJobs.size
            val availableSlots = maxConcurrency - currentActiveCount

            _activeDownloadCount.value = currentActiveCount

            if (availableSlots <= 0) {
                return
            }

            if (!networkMonitor.isOnline()) {
                return
            }

            val queuedTasks = repository.getQueuedTasks()
            val tasksToStart = queuedTasks.take(availableSlots)

            for (task in tasksToStart) {
                if (!activeJobs.containsKey(task.id)) {
                    val job = scope.launch {
                        executeDownloadTask(task.id)
                    }
                    activeJobs[task.id] = job
                    _activeDownloadCount.value = activeJobs.size
                }
            }
        }
    }

    private suspend fun executeDownloadTask(taskId: String) {
        val task = repository.getTaskByIdSync(taskId) ?: run {
            activeJobs.remove(taskId)
            _activeDownloadCount.value = activeJobs.size
            return
        }

        // Storage Check: Minimum 50MB free
        if (!MediaStoreHelper.hasEnoughStorageSpace(context)) {
            val failedTask = task.copy(
                status = DownloadStatus.FAILED,
                errorMessage = "Insufficient storage space available (minimum 50MB required)."
            )
            repository.updateTask(failedTask)
            activeJobs.remove(taskId)
            _activeDownloadCount.value = activeJobs.size
            processQueue()
            return
        }

        // Update state to PREPARING
        repository.updateTask(task.copy(status = DownloadStatus.PREPARING))
        DownloadForegroundService.startOrUpdate(context, taskId, task.title, 0, DownloadStatus.PREPARING, "")

        // Update state to DOWNLOADING
        repository.updateTask(task.copy(status = DownloadStatus.DOWNLOADING))
        DownloadForegroundService.startOrUpdate(context, taskId, task.title, task.progress.toInt(), DownloadStatus.DOWNLOADING, "")

        val cutMode = if (task.cutMode.equals("precise", ignoreCase = true)) CutMode.PRECISE_CUT else CutMode.FAST_CUT
        val request = DownloadRequest(
            id = task.id,
            url = task.url,
            formatSelector = task.formatId,
            startTime = task.startTime,
            endTime = task.endTime,
            cutMode = cutMode,
            title = task.title,
            thumbnailUrl = task.thumbnailUrl,
            formatDescription = task.formatDescription,
            isAudioOnly = task.isAudioOnly,
            isVideoOnly = task.isVideoOnly,
            downloadSubtitles = task.downloadSubtitles,
            subtitleLanguage = task.subtitleLanguage
        )

        val smoother = speedSmoothers.getOrPut(taskId) { SpeedSmoother() }

        val result = downloadEngine.download(request) { progress ->
            handleProgressUpdate(
                taskId = taskId,
                title = task.title,
                progress = progress.progressPercentage,
                speed = progress.speed,
                eta = progress.eta,
                downloadedBytes = progress.downloadedBytes,
                totalBytes = progress.totalBytes,
                smoother = smoother
            )
        }

        activeJobs.remove(taskId)
        speedSmoothers.remove(taskId)
        lastProgressUpdateTimes.remove(taskId)
        lastReportedProgress.remove(taskId)
        lastNotificationTimes.remove(taskId)
        _activeDownloadCount.value = activeJobs.size

        result.fold(
            onSuccess = { finalFile ->
                val (uri, savedPath) = MediaStoreHelper.saveToPublicDownloads(context, finalFile, task.title)
                if (finalFile.exists() && savedPath != finalFile.absolutePath) {
                    finalFile.delete()
                }

                val current = repository.getTaskByIdSync(taskId)
                val finalFileSize = if (!savedPath.isNullOrBlank()) {
                    val f = File(savedPath)
                    if (f.exists()) CleanupManager.formatFileSize(f.length()) else ""
                } else if (finalFile.exists()) {
                    CleanupManager.formatFileSize(finalFile.length())
                } else ""

                val completedTask = current?.copy(
                    status = DownloadStatus.COMPLETED,
                    progress = 100f,
                    contentUri = uri?.toString(),
                    filePath = savedPath ?: finalFile.absolutePath,
                    downloadSpeed = "",
                    eta = "",
                    downloadedSize = if (current.downloadedSize.isNotBlank()) current.downloadedSize else finalFileSize,
                    totalSize = if (current.totalSize.isNotBlank()) current.totalSize else finalFileSize,
                    completedAt = System.currentTimeMillis()
                )
                if (completedTask != null) {
                    repository.updateTask(completedTask)
                }
                DownloadForegroundService.onTaskCompleted(context, taskId, task.title, uri?.toString())
            },
            onFailure = { error ->
                handleDownloadFailure(taskId, task, error)
            }
        )

        processQueue()
    }

    private suspend fun handleDownloadFailure(taskId: String, originalTask: DownloadTaskEntity, error: Throwable) {
        val isCancelled = error is DownloadError.Cancelled ||
                error.message?.contains("destroy", ignoreCase = true) == true ||
                error.message?.contains("interrupted", ignoreCase = true) == true ||
                error.message?.contains("cancel", ignoreCase = true) == true

        val currentTask = repository.getTaskByIdSync(taskId) ?: originalTask
        val isExplicitlyPaused = currentTask.status == DownloadStatus.PAUSED

        if (isExplicitlyPaused) {
            return
        }

        val finalStatus = if (isCancelled) DownloadStatus.CANCELLED else DownloadStatus.FAILED
        val errorTask = currentTask.copy(
            status = finalStatus,
            downloadSpeed = "",
            eta = "",
            errorMessage = error.localizedMessage ?: "Download failed"
        )
        repository.updateTask(errorTask)

        if (finalStatus == DownloadStatus.FAILED) {
            val isRetryable = RetryPolicy.isRetryable(error)
            val canAutoRetry = appSettings.autoRetry.value && currentTask.retryCount < RetryPolicy.MAX_RETRIES && isRetryable

            if (canAutoRetry) {
                val delayMs = RetryPolicy.getBackoffDelayMs(currentTask.retryCount)
                scope.launch {
                    delay(delayMs)
                    retryDownload(taskId)
                }
            } else {
                DownloadForegroundService.onTaskFailed(
                    context, taskId, originalTask.title, error.localizedMessage ?: "Download error"
                )
            }
        } else {
            DownloadForegroundService.updateOrDismissIfIdle(
                context, taskId, originalTask.title, finalStatus, 0, ""
            )
        }
    }

    private fun handleProgressUpdate(
        taskId: String,
        title: String,
        progress: Float,
        speed: String,
        eta: String,
        downloadedBytes: Long,
        totalBytes: Long,
        smoother: SpeedSmoother
    ) {
        val now = System.currentTimeMillis()
        val lastTime = lastProgressUpdateTimes[taskId] ?: 0L
        val lastProg = lastReportedProgress[taskId] ?: 0f
        val progressDelta = Math.abs(progress - lastProg)

        val isSignificant = progressDelta >= 0.5f || progress >= 100f || (now - lastTime >= 400L)
        if (!isSignificant) {
            return
        }

        lastProgressUpdateTimes[taskId] = now
        lastReportedProgress[taskId] = progress

        val speedText = speed
        val downloadedText = if (downloadedBytes > 0) CleanupManager.formatFileSize(downloadedBytes) else ""
        val totalText = if (totalBytes > 0) CleanupManager.formatFileSize(totalBytes) else ""

        scope.launch {
            repository.updateProgress(
                id = taskId,
                status = DownloadStatus.DOWNLOADING,
                progress = progress,
                downloadSpeed = speedText,
                eta = eta,
                downloadedSize = downloadedText,
                totalSize = totalText
            )

            val lastNotif = lastNotificationTimes[taskId] ?: 0L
            if (now - lastNotif >= 1000L || progress >= 100f) {
                lastNotificationTimes[taskId] = now
                DownloadForegroundService.startOrUpdate(
                    context = context,
                    taskId = taskId,
                    title = title,
                    progress = progress.toInt(),
                    status = DownloadStatus.DOWNLOADING,
                    speed = speedText
                )
            }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: DownloadQueueManager? = null

        fun getInstance(context: Context): DownloadQueueManager {
            return INSTANCE ?: synchronized(this) {
                val instance = DownloadQueueManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
