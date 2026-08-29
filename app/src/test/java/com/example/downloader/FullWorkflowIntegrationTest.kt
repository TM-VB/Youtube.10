package com.example.downloader

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.DownloadTaskEntity
import com.example.data.repository.DownloadRepository
import com.example.domain.model.DownloadStatus
import com.example.domain.model.FormatInfo
import com.example.domain.model.VideoInfo
import com.example.domain.util.FileNameSanitizer
import com.example.domain.validator.TimeValidationResult
import com.example.domain.validator.TimeValidator
import com.example.domain.validator.UrlValidator
import com.example.ytdlp.SmartFormatEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FullWorkflowIntegrationTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: DownloadRepository
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DownloadRepository(db.downloadTaskDao())
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun testCompleteDownloadPipelineFlow() = runBlocking {
        // 1. URL Validation
        val rawUrl = "https://www.youtube.com/watch?v=sample123"
        assertTrue(UrlValidator.isValidUrl(rawUrl))

        // 2. Simulated Video Info & Formats
        val formats = listOf(
            FormatInfo(formatId = "137", extension = "mp4", width = 1920, height = 1080, resolution = "1080p", filesize = 50_000_000L, vcodec = "avc1", acodec = "none", hasVideo = true, hasAudio = false),
            FormatInfo(formatId = "136", extension = "mp4", width = 1280, height = 720, resolution = "720p", filesize = 25_000_000L, vcodec = "avc1", acodec = "none", hasVideo = true, hasAudio = false),
            FormatInfo(formatId = "18", extension = "mp4", width = 640, height = 360, resolution = "360p", filesize = 10_000_000L, vcodec = "avc1", acodec = "mp4a", hasVideo = true, hasAudio = true),
            FormatInfo(formatId = "140", extension = "m4a", resolution = "audio only", filesize = 3_000_000L, vcodec = "none", acodec = "mp4a", hasVideo = false, hasAudio = true)
        )
        val videoInfo = VideoInfo(
            id = "sample123",
            title = "Test Android Architecture: Jetpack Compose & M3",
            uploader = "Google Developers",
            duration = 180L,
            thumbnail = "https://example.com/thumb.jpg",
            webpageUrl = rawUrl,
            formats = formats
        )

        // 3. Smart Format Selection
        val bestSelection = SmartFormatEngine.selectBestQuality(videoInfo.formats)
        assertNotNull(bestSelection.videoFormat)
        assertEquals("137", bestSelection.videoFormat?.formatId)
        assertNotNull(bestSelection.audioFormat)
        assertEquals("140", bestSelection.audioFormat?.formatId)

        // 4. Time Trimming Check
        val timeTrimResult = TimeValidator.validate("00:00:10", "00:01:00", videoInfo.duration?.toInt())
        assertTrue(timeTrimResult is TimeValidationResult.Success)
        val successTrim = timeTrimResult as TimeValidationResult.Success
        assertEquals(10, successTrim.startSeconds)
        assertEquals(60, successTrim.endSeconds)

        // 5. Filename Sanitization
        val sanitizedFilename = FileNameSanitizer.sanitize(videoInfo.title, "mp4")
        assertEquals("Test Android Architecture_ Jetpack Compose & M3.mp4", sanitizedFilename)

        // 6. Task Insertion into Queue Database
        val taskId = "task_integration_001"
        val task = DownloadTaskEntity(
            id = taskId,
            url = rawUrl,
            title = videoInfo.title,
            thumbnailUrl = videoInfo.thumbnail,
            formatId = "137+140",
            formatDescription = "1080p (Video + Audio)",
            isAudioOnly = false,
            isVideoOnly = false,
            status = DownloadStatus.DOWNLOADING,
            progress = 0f,
            queueOrder = 1L,
            startTime = "00:00:10",
            endTime = "00:01:00",
            createdAt = System.currentTimeMillis()
        )
        repository.insertTask(task)

        // 7. Throttled Progress Updates
        repository.updateProgress(taskId, DownloadStatus.DOWNLOADING, 45.5f, "4.8 MB/s", "00:15")
        var current = repository.getTaskByIdSync(taskId)
        assertEquals(45.5f, current?.progress)
        assertEquals(DownloadStatus.DOWNLOADING, current?.status)

        // 8. FFmpeg Merge Processing State
        repository.updateProgress(taskId, DownloadStatus.PROCESSING_FFMPEG, 99.0f, "", "Processing...")
        current = repository.getTaskByIdSync(taskId)
        assertEquals(DownloadStatus.PROCESSING_FFMPEG, current?.status)

        // 9. Completion State
        val completedTask = current!!.copy(
            status = DownloadStatus.COMPLETED,
            progress = 100f,
            downloadedSize = "53.0 MB",
            totalSize = "53.0 MB",
            completedAt = System.currentTimeMillis()
        )
        repository.updateTask(completedTask)

        val finalTask = repository.getTaskByIdSync(taskId)
        assertEquals(DownloadStatus.COMPLETED, finalTask?.status)
        assertEquals(100f, finalTask?.progress)
    }

    @Test
    fun testProcessDeathRecoveryWorkflow() = runBlocking {
        // Insert tasks with active status
        repository.insertTask(
            DownloadTaskEntity(
                id = "crashed_1",
                url = "https://youtube.com/watch?v=crashed1",
                title = "Video 1",
                formatId = "18",
                formatDescription = "360p",
                status = DownloadStatus.DOWNLOADING,
                progress = 60f
            )
        )
        repository.insertTask(
            DownloadTaskEntity(
                id = "crashed_2",
                url = "https://youtube.com/watch?v=crashed2",
                title = "Video 2",
                formatId = "22",
                formatDescription = "720p",
                status = DownloadStatus.PROCESSING_FFMPEG,
                progress = 95f
            )
        )

        // Simulate app restart recovery using DAO method
        val modifiedCount = repository.markActiveTasksAsInterrupted()
        assertEquals(2, modifiedCount)

        val recovered1 = repository.getTaskByIdSync("crashed_1")
        val recovered2 = repository.getTaskByIdSync("crashed_2")
        assertEquals(DownloadStatus.INTERRUPTED, recovered1?.status)
        assertEquals(DownloadStatus.INTERRUPTED, recovered2?.status)
    }
}
