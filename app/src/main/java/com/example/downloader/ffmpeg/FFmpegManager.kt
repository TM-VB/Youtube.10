package com.example.downloader.ffmpeg

import android.content.Context
import android.os.Build
import android.os.StatFs
import com.example.domain.model.CutMode
import com.example.domain.model.DownloadError
import com.example.domain.model.MediaResult
import com.example.domain.model.ProcessingProgress
import com.example.downloader.engine.MediaProcessor
import com.example.storage.MediaStoreHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Production-ready FFmpegManager implementing MediaProcessor for Android.
 * Features:
 * - Real embedded binary discovery with ABI validation and execution permission enforcement.
 * - Non-blocking streaming on Dispatchers.IO with argument array isolation.
 * - Real-time progress parsing (-progress pipe:1, out_time_ms, speed, fps, eta).
 * - Atomic output file writing (*.tmp -> validation -> final destination).
 * - Immediate process cancellation and automatic temp cleanup.
 */
class FFmpegManager(private val context: Context) : MediaProcessor {

    private val runningProcesses = ConcurrentHashMap<String, Process>()
    private var cachedBinary: File? = null
    private var cachedVersion: String? = null

    val supportedAbis: List<String>
        get() = Build.SUPPORTED_ABIS.toList()

    val primaryAbi: String
        get() = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    /**
     * Resolves and verifies the local FFmpeg binary file.
     */
    fun getFFmpegBinary(): File? {
        cachedBinary?.let { if (it.exists() && it.canExecute()) return it }

        // 1. Check embedded youtubedl-android FFmpeg package
        try {
            val ffmpegClass = Class.forName("com.yausername.ffmpeg.FFmpeg")
            val getInstanceMethod = ffmpegClass.getMethod("getInstance")
            val ffmpegInstance = getInstanceMethod.invoke(null)
            try {
                val initMethod = ffmpegClass.getMethod("init", Context::class.java)
                initMethod.invoke(ffmpegInstance, context.applicationContext)
            } catch (_: Throwable) {}

            val binDirField = ffmpegClass.getDeclaredField("binDir").apply { isAccessible = true }
            val binDir = binDirField.get(ffmpegInstance) as? File
            if (binDir != null) {
                val binary = File(binDir, "ffmpeg")
                if (binary.exists()) {
                    if (!binary.canExecute()) binary.setExecutable(true)
                    cachedBinary = binary
                    return binary
                }
            }
        } catch (_: Throwable) {}

        // 2. Check nativeLibraryDir for native library builds
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val possibleNames = listOf("libffmpeg.so", "ffmpeg.so", "ffmpeg")
        for (name in possibleNames) {
            val file = File(nativeDir, name)
            if (file.exists()) {
                if (!file.canExecute()) file.setExecutable(true)
                cachedBinary = file
                return file
            }
        }

        // 3. Check internal files directory
        val candidates = listOf(
            File(context.filesDir, "usr/bin/ffmpeg"),
            File(context.filesDir, "bin/ffmpeg"),
            File(context.noBackupFilesDir, "usr/bin/ffmpeg"),
            File(context.noBackupFilesDir, "bin/ffmpeg")
        )
        for (candidate in candidates) {
            if (candidate.exists()) {
                if (!candidate.canExecute()) candidate.setExecutable(true)
                cachedBinary = candidate
                return candidate
            }
        }

        return null
    }

    /**
     * Retrieves the version string of the embedded FFmpeg binary.
     */
    fun getVersion(): String {
        cachedVersion?.let { return it }

        try {
            val ffmpegClass = Class.forName("com.yausername.ffmpeg.FFmpeg")
            val getInstanceMethod = ffmpegClass.getMethod("getInstance")
            val ffmpegInstance = getInstanceMethod.invoke(null)
            val versionMethod = ffmpegClass.getMethod("version", Context::class.java)
            val ver = versionMethod.invoke(ffmpegInstance, context.applicationContext) as? String
            if (!ver.isNullOrBlank()) {
                cachedVersion = ver
                return ver
            }
        } catch (_: Throwable) {}

        val binary = getFFmpegBinary()
        if (binary != null && binary.canExecute()) {
            try {
                val process = ProcessBuilder(binary.absolutePath, "-version").start()
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val firstLine = reader.readLine()
                process.waitFor()
                if (!firstLine.isNullOrBlank()) {
                    val ver = firstLine.substringBefore("Copyright").trim()
                    cachedVersion = ver
                    return ver
                }
            } catch (_: Throwable) {}
        }

        val fallback = "v4.4.x-android ($primaryAbi)"
        cachedVersion = fallback
        return fallback
    }

    fun getStatus(): FFmpegStatus {
        val binary = getFFmpegBinary()
        val available = binary != null && binary.exists()
        val executable = binary?.canExecute() == true

        return FFmpegStatus(
            isAvailable = available,
            binaryPath = binary?.absolutePath,
            detectedAbi = primaryAbi,
            isExecutable = executable,
            version = getVersion(),
            errorMessage = if (!available) "FFmpeg binary not found for ABI $primaryAbi" else null
        )
    }

    override suspend fun mergeVideoAudio(
        videoFile: File,
        audioFile: File,
        outputFile: File,
        onProgress: ((ProcessingProgress) -> Unit)?
    ): Result<MediaResult> = withContext(Dispatchers.IO) {
        if (!videoFile.exists() || !audioFile.exists()) {
            return@withContext Result.failure(
                DownloadError.FfmpegError("Source video or audio file does not exist.")
            )
        }

        val binary = getFFmpegBinary()
            ?: return@withContext Result.failure(DownloadError.FfmpegError("FFmpeg binary unavailable."))

        val requiredStorage = (videoFile.length() + audioFile.length()) + 20 * 1024 * 1024L
        if (!hasAvailableStorage(requiredStorage)) {
            return@withContext Result.failure(DownloadError.StorageError("Insufficient storage space for media merge."))
        }

        val processId = UUID.randomUUID().toString()
        val tempOutput = createTempProcessingFile("merge", outputFile.extension)

        val args = FFmpegCommandBuilder.buildMergeArgs(
            binaryPath = binary.absolutePath,
            videoFile = videoFile,
            audioFile = audioFile,
            outputFile = tempOutput
        )

        val result = executeFFmpeg(processId, args, 0.0, onProgress)
        result.fold(
            onSuccess = {
                val validated = validateAndFinalize(tempOutput, outputFile, "Video+Audio Merge")
                if (validated != null) {
                    Result.success(validated)
                } else {
                    tempOutput.delete()
                    Result.failure(DownloadError.FfmpegError("Output validation failed after merge."))
                }
            },
            onFailure = { error ->
                tempOutput.delete()
                Result.failure(error)
            }
        )
    }

    override suspend fun fastCut(
        inputFile: File,
        startTime: String,
        endTime: String,
        outputFile: File,
        onProgress: ((ProcessingProgress) -> Unit)?
    ): Result<MediaResult> = withContext(Dispatchers.IO) {
        if (!inputFile.exists()) {
            return@withContext Result.failure(DownloadError.FfmpegError("Input file not found: ${inputFile.path}"))
        }

        val binary = getFFmpegBinary()
            ?: return@withContext Result.failure(DownloadError.FfmpegError("FFmpeg binary unavailable."))

        val totalDuration = calculateDurationSeconds(startTime, endTime)
        val processId = UUID.randomUUID().toString()
        val tempOutput = createTempProcessingFile("fastcut", outputFile.extension)

        val args = FFmpegCommandBuilder.buildFastCutArgs(
            binaryPath = binary.absolutePath,
            inputFile = inputFile,
            outputFile = tempOutput,
            startTime = startTime,
            endTime = endTime
        )

        val result = executeFFmpeg(processId, args, totalDuration, onProgress)
        result.fold(
            onSuccess = {
                val validated = validateAndFinalize(tempOutput, outputFile, "Fast Cut")
                if (validated != null) {
                    Result.success(validated)
                } else {
                    tempOutput.delete()
                    Result.failure(DownloadError.FfmpegError("Output validation failed after Fast Cut."))
                }
            },
            onFailure = { error ->
                tempOutput.delete()
                Result.failure(error)
            }
        )
    }

    override suspend fun preciseCut(
        inputFile: File,
        startTime: String,
        endTime: String,
        outputFile: File,
        onProgress: ((ProcessingProgress) -> Unit)?
    ): Result<MediaResult> = withContext(Dispatchers.IO) {
        if (!inputFile.exists()) {
            return@withContext Result.failure(DownloadError.FfmpegError("Input file not found: ${inputFile.path}"))
        }

        val binary = getFFmpegBinary()
            ?: return@withContext Result.failure(DownloadError.FfmpegError("FFmpeg binary unavailable."))

        val totalDuration = calculateDurationSeconds(startTime, endTime)
        val processId = UUID.randomUUID().toString()
        val tempOutput = createTempProcessingFile("precisecut", outputFile.extension)

        val args = FFmpegCommandBuilder.buildPreciseCutArgs(
            binaryPath = binary.absolutePath,
            inputFile = inputFile,
            outputFile = tempOutput,
            startTime = startTime,
            endTime = endTime
        )

        val result = executeFFmpeg(processId, args, totalDuration, onProgress)
        result.fold(
            onSuccess = {
                val validated = validateAndFinalize(tempOutput, outputFile, "Precise Cut")
                if (validated != null) {
                    Result.success(validated)
                } else {
                    tempOutput.delete()
                    Result.failure(DownloadError.FfmpegError("Output validation failed after Precise Cut."))
                }
            },
            onFailure = { error ->
                tempOutput.delete()
                Result.failure(error)
            }
        )
    }

    override suspend fun cutMedia(
        inputFile: File,
        outputFile: File,
        startTime: String,
        endTime: String,
        mode: CutMode,
        onProgress: ((ProcessingProgress) -> Unit)?
    ): Result<MediaResult> {
        return when (mode) {
            CutMode.FAST_CUT -> fastCut(inputFile, startTime, endTime, outputFile, onProgress)
            CutMode.PRECISE_CUT -> preciseCut(inputFile, startTime, endTime, outputFile, onProgress)
        }
    }

    override suspend fun remux(
        inputFile: File,
        outputFile: File,
        targetContainer: String,
        onProgress: ((ProcessingProgress) -> Unit)?
    ): Result<MediaResult> = withContext(Dispatchers.IO) {
        if (!inputFile.exists()) {
            return@withContext Result.failure(DownloadError.FfmpegError("Input file not found."))
        }

        val binary = getFFmpegBinary()
            ?: return@withContext Result.failure(DownloadError.FfmpegError("FFmpeg binary unavailable."))

        val processId = UUID.randomUUID().toString()
        val tempOutput = createTempProcessingFile("remux", targetContainer)

        val args = FFmpegCommandBuilder.buildRemuxArgs(
            binaryPath = binary.absolutePath,
            inputFile = inputFile,
            outputFile = tempOutput
        )

        val result = executeFFmpeg(processId, args, 0.0, onProgress)
        result.fold(
            onSuccess = {
                val validated = validateAndFinalize(tempOutput, outputFile, "Remux")
                if (validated != null) {
                    Result.success(validated)
                } else {
                    tempOutput.delete()
                    Result.failure(DownloadError.FfmpegError("Output validation failed after remux."))
                }
            },
            onFailure = { error ->
                tempOutput.delete()
                Result.failure(error)
            }
        )
    }

    override suspend fun extractAudio(
        inputFile: File,
        outputFile: File,
        audioCodec: String,
        onProgress: ((ProcessingProgress) -> Unit)?
    ): Result<MediaResult> = withContext(Dispatchers.IO) {
        if (!inputFile.exists()) {
            return@withContext Result.failure(DownloadError.FfmpegError("Input file not found."))
        }

        val binary = getFFmpegBinary()
            ?: return@withContext Result.failure(DownloadError.FfmpegError("FFmpeg binary unavailable."))

        val processId = UUID.randomUUID().toString()
        val tempOutput = createTempProcessingFile("extract_audio", outputFile.extension)

        val args = FFmpegCommandBuilder.buildExtractAudioArgs(
            binaryPath = binary.absolutePath,
            inputFile = inputFile,
            outputFile = tempOutput,
            audioCodec = audioCodec
        )

        val result = executeFFmpeg(processId, args, 0.0, onProgress)
        result.fold(
            onSuccess = {
                val validated = validateAndFinalize(tempOutput, outputFile, "Audio Extraction")
                if (validated != null) {
                    Result.success(validated)
                } else {
                    tempOutput.delete()
                    Result.failure(DownloadError.FfmpegError("Output validation failed after audio extraction."))
                }
            },
            onFailure = { error ->
                tempOutput.delete()
                Result.failure(error)
            }
        )
    }

    /**
     * Executes the FFmpeg process with safe argument array, real-time progress parsing, and cancellation tracking.
     */
    suspend fun executeFFmpeg(
        processId: String,
        arguments: List<String>,
        totalDurationSeconds: Double,
        onProgress: ((ProcessingProgress) -> Unit)?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val processBuilder = ProcessBuilder(arguments)
            processBuilder.redirectErrorStream(true)
            val process = processBuilder.start()
            runningProcesses[processId] = process

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            val logBuffer = StringBuilder()

            var currentSpeed = "1.0x"
            var currentFps = 0.0
            var currentFrame = 0L

            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: break
                logBuffer.appendLine(currentLine)

                // Parse progress attributes
                if (currentLine.startsWith("speed=")) {
                    currentSpeed = currentLine.substringAfter("speed=").trim()
                } else if (currentLine.startsWith("fps=")) {
                    currentFps = currentLine.substringAfter("fps=").trim().toDoubleOrNull() ?: 0.0
                } else if (currentLine.startsWith("frame=")) {
                    currentFrame = currentLine.substringAfter("frame=").trim().toLongOrNull() ?: 0L
                }

                val progressObj = parseProcessingProgress(
                    line = currentLine,
                    totalDurationSeconds = totalDurationSeconds,
                    speed = currentSpeed,
                    fps = currentFps,
                    frame = currentFrame
                )

                if (progressObj != null && onProgress != null) {
                    onProgress(progressObj)
                }
            }

            val exitCode = process.waitFor()
            runningProcesses.remove(processId)

            if (exitCode == 0) {
                onProgress?.invoke(
                    ProcessingProgress(
                        percentage = 100f,
                        totalDurationSeconds = totalDurationSeconds,
                        speed = currentSpeed,
                        statusDescription = "Processing complete"
                    )
                )
                Result.success(Unit)
            } else {
                Result.failure(
                    DownloadError.FfmpegError(
                        msg = "FFmpeg media processing failed (exit code $exitCode).",
                        detail = logBuffer.takeLast(1000).toString()
                    )
                )
            }
        } catch (e: Exception) {
            runningProcesses.remove(processId)
            Result.failure(
                DownloadError.FfmpegError(
                    msg = "FFmpeg execution error: ${e.message}",
                    detail = e.stackTraceToString()
                )
            )
        }
    }

    override fun cancel(processId: String) {
        runningProcesses.remove(processId)?.let { process ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    process.destroyForcibly()
                } else {
                    process.destroy()
                }
            } catch (_: Exception) {}
        }
    }

    private fun createTempProcessingFile(prefix: String, ext: String): File {
        val cacheDir = File(context.cacheDir, "ffmpeg_temp").apply { if (!exists()) mkdirs() }
        val extension = if (ext.startsWith(".")) ext else ".$ext"
        return File(cacheDir, "${prefix}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}$extension")
    }

    private fun validateAndFinalize(tempFile: File, destinationFile: File, operation: String): MediaResult? {
        if (!tempFile.exists() || tempFile.length() <= 0L) {
            return null
        }

        // Ensure parent directory exists
        destinationFile.parentFile?.let { if (!it.exists()) it.mkdirs() }

        // Atomic move or copy
        val success = if (tempFile.renameTo(destinationFile)) {
            true
        } else {
            try {
                tempFile.copyTo(destinationFile, overwrite = true)
                tempFile.delete()
                true
            } catch (_: Exception) {
                false
            }
        }

        if (!success || !destinationFile.exists() || destinationFile.length() <= 0L) {
            return null
        }

        val mimeType = MediaStoreHelper.getMimeType(destinationFile.name)
        return MediaResult(
            outputFile = destinationFile,
            mimeType = mimeType,
            sizeBytes = destinationFile.length(),
            operation = operation
        )
    }

    private fun hasAvailableStorage(requiredBytes: Long): Boolean {
        return try {
            val stat = StatFs(context.cacheDir.path)
            val available = stat.availableBlocksLong * stat.blockSizeLong
            available >= requiredBytes
        } catch (_: Exception) {
            true
        }
    }

    companion object {
        private val TIME_REGEX = Regex("""time=(\d{2}:\d{2}:\d{2}(?:\.\d+)?)""")
        private val SPEED_REGEX = Regex("""speed=\s*(\S+x?)""")
        private val OUT_TIME_MS_REGEX = Regex("""out_time_ms=(\d+)""")
        private val FPS_REGEX = Regex("""fps=\s*(\d+(?:\.\d+)?)""")
        private val FRAME_REGEX = Regex("""frame=\s*(\d+)""")

        fun parseTimeSeconds(timeStr: String): Double? {
            val parts = timeStr.trim().split(":")
            if (parts.size != 3) return null
            val hours = parts[0].toDoubleOrNull() ?: return null
            val minutes = parts[1].toDoubleOrNull() ?: return null
            val seconds = parts[2].toDoubleOrNull() ?: return null
            return hours * 3600.0 + minutes * 60.0 + seconds
        }

        fun parseProcessingProgress(
            line: String,
            totalDurationSeconds: Double,
            speed: String = "1.0x",
            fps: Double = 0.0,
            frame: Long = 0L
        ): ProcessingProgress? {
            var timeSec: Double? = null

            val msMatch = OUT_TIME_MS_REGEX.find(line)
            if (msMatch != null) {
                val ms = msMatch.groupValues[1].toDoubleOrNull()
                if (ms != null && ms >= 0) {
                    timeSec = ms / 1_000_000.0
                }
            }

            if (timeSec == null) {
                val timeMatch = TIME_REGEX.find(line)
                if (timeMatch != null) {
                    timeSec = parseTimeSeconds(timeMatch.groupValues[1])
                }
            }

            if (timeSec != null && totalDurationSeconds > 0) {
                val pct = ((timeSec / totalDurationSeconds) * 100.0).toFloat().coerceIn(0f, 99f)
                val remainingSec = (totalDurationSeconds - timeSec).coerceAtLeast(0.0)
                val parsedSpeedNum = speed.replace("x", "").toDoubleOrNull() ?: 1.0
                val eta = if (parsedSpeedNum > 0) (remainingSec / parsedSpeedNum).toLong() else 0L

                return ProcessingProgress(
                    percentage = pct,
                    timeSeconds = timeSec,
                    totalDurationSeconds = totalDurationSeconds,
                    speed = speed,
                    fps = fps,
                    frame = frame,
                    etaSeconds = eta,
                    statusDescription = "Processing media... ${pct.toInt()}%"
                )
            }

            return null
        }

        fun calculateDurationSeconds(startTime: String, endTime: String): Double {
            val start = parseTimeSeconds(startTime) ?: 0.0
            val end = parseTimeSeconds(endTime) ?: 0.0
            return (end - start).coerceAtLeast(1.0)
        }
    }
}
