package com.example.data.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.domain.util.FileNameSanitizer
import com.example.downloader.engine.StorageManager
import com.example.storage.MediaStoreHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * Concrete implementation of StorageManager using modern Android Scoped Storage and MediaStore APIs.
 * Automatically saves files to the user's public 'Downloads/DownloadVideos' directory.
 */
class AndroidStorageManager(private val context: Context) : StorageManager {

    override fun getTempDirectory(): File {
        val tempDir = File(context.cacheDir, "downloads_temp")
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }
        return tempDir
    }

    override suspend fun saveVideoToMediaStore(
        tempFile: File,
        displayName: String,
        mimeType: String
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val extension = tempFile.extension.ifEmpty { "mp4" }
            val sanitizedName = FileNameSanitizer.sanitize(displayName, extension)

            val resolver = context.contentResolver
            val isVideo = mimeType.startsWith("video/")
            val targetDir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_DOWNLOADS

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, sanitizedName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "$targetDir/DownloadVideos"
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                if (isVideo) {
                    put(MediaStore.Video.Media.TITLE, displayName)
                }
            }

            val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val itemUri = resolver.insert(collectionUri, contentValues)
                ?: return@withContext Result.failure(Exception("Failed to insert MediaStore item"))

            resolver.openOutputStream(itemUri)?.use { outputStream ->
                FileInputStream(tempFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return@withContext Result.failure(Exception("Failed to open output stream for $itemUri"))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)
            }

            // Notify media scanner
            try {
                val publicPath = "${Environment.getExternalStoragePublicDirectory(targetDir)}/DownloadVideos/$sanitizedName"
                android.media.MediaScannerConnection.scanFile(
                    context.applicationContext,
                    arrayOf(publicPath),
                    arrayOf(mimeType),
                    null
                )
            } catch (_: Exception) {}

            // Cleanup temp file after successful persist
            if (tempFile.exists()) {
                tempFile.delete()
            }

            Result.success(itemUri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun openMediaFile(context: Context, filePath: String?, contentUriStr: String?) {
        MediaStoreHelper.openFile(context, filePath, contentUriStr)
    }

    override suspend fun cleanTempFiles() {
        withContext(Dispatchers.IO) {
            val tempDir = getTempDirectory()
            tempDir.listFiles()?.forEach { file ->
                if (file.isFile && System.currentTimeMillis() - file.lastModified() > 24 * 3600 * 1000) {
                    file.delete()
                }
            }
        }
    }
}
