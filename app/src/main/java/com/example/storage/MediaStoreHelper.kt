package com.example.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.example.domain.util.FileNameSanitizer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object MediaStoreHelper {

    fun getTempDownloadDir(context: Context): File {
        val dir = File(context.cacheDir, "ytdlp_downloads")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "opus" -> "audio/opus"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "video/mp4"
        }
    }

    fun isVideoMime(mimeType: String): Boolean = mimeType.startsWith("video/")
    fun isAudioMime(mimeType: String): Boolean = mimeType.startsWith("audio/")

    /**
     * Copies a completed download file to the public Movies/DownloadVideos or Music/DownloadVideos directory using MediaStore.
     * Triggers MediaScannerConnection so Android Gallery & Music players instantly generate thumbnails and metadata.
     */
    fun saveToPublicDownloads(context: Context, sourceFile: File, rawTitle: String): Pair<Uri?, String?> {
        if (!sourceFile.exists() || sourceFile.length() < 512L) {
            return Pair(null, null)
        }

        val extension = sourceFile.extension.ifBlank { "mp4" }
        val displayName = FileNameSanitizer.sanitize(rawTitle, extension)
        val mimeType = getMimeType(displayName)
        val isVideo = isVideoMime(mimeType)
        val isAudio = isAudioMime(mimeType)

        val targetDir = when {
            isVideo -> Environment.DIRECTORY_MOVIES
            isAudio -> Environment.DIRECTORY_MUSIC
            else -> Environment.DIRECTORY_DOWNLOADS
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collectionUri = when {
                    isVideo -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    isAudio -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$targetDir/DownloadVideos")
                    put(MediaStore.MediaColumns.SIZE, sourceFile.length())
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    if (isVideo) {
                        put(MediaStore.Video.Media.TITLE, rawTitle)
                    } else if (isAudio) {
                        put(MediaStore.Audio.Media.TITLE, rawTitle)
                    }
                }

                val uri = context.contentResolver.insert(collectionUri, values)

                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(sourceFile).use { input ->
                            input.copyTo(out, bufferSize = 64 * 1024)
                            out.flush()
                        }
                    }

                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    values.put(MediaStore.MediaColumns.SIZE, sourceFile.length())
                    context.contentResolver.update(uri, values, null, null)

                    val publicPath = "${Environment.getExternalStoragePublicDirectory(targetDir)}/DownloadVideos/$displayName"

                    // Scan file with MediaScanner to guarantee Gallery / Photos thumbnail generation
                    try {
                        MediaScannerConnection.scanFile(
                            context.applicationContext,
                            arrayOf(publicPath),
                            arrayOf(mimeType),
                            null
                        )
                    } catch (_: Exception) {}

                    return Pair(uri, publicPath)
                }
            } else {
                val publicDir = File(
                    Environment.getExternalStoragePublicDirectory(targetDir),
                    "DownloadVideos"
                )
                if (!publicDir.exists()) {
                    publicDir.mkdirs()
                }
                val destFile = File(publicDir, displayName)
                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                        output.flush()
                    }
                }
                val uri = Uri.fromFile(destFile)

                try {
                    MediaScannerConnection.scanFile(
                        context.applicationContext,
                        arrayOf(destFile.absolutePath),
                        arrayOf(mimeType),
                        null
                    )
                } catch (_: Exception) {}

                return Pair(uri, destFile.absolutePath)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback: use sourceFile in app cache if valid
        return if (sourceFile.exists() && sourceFile.length() > 512L) {
            Pair(null, sourceFile.absolutePath)
        } else {
            Pair(null, null)
        }
    }

    /**
     * Opens downloaded file with system video or audio player
     */
    fun openFile(context: Context, filePath: String?, contentUriStr: String?) {
        try {
            val uri: Uri = when {
                !contentUriStr.isNullOrBlank() -> Uri.parse(contentUriStr)
                !filePath.isNullOrBlank() -> {
                    val file = File(filePath)
                    if (file.exists()) {
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.provider",
                            file
                        )
                    } else return
                }
                else -> return
            }

            val mimeType = getMimeType(filePath ?: "video.mp4")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Open with").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Shares the downloaded media file with other apps via system share sheet
     */
    fun shareFile(context: Context, filePath: String?, contentUriStr: String?) {
        try {
            val uri: Uri = when {
                !contentUriStr.isNullOrBlank() -> Uri.parse(contentUriStr)
                !filePath.isNullOrBlank() -> {
                    val file = File(filePath)
                    if (file.exists()) {
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.provider",
                            file
                        )
                    } else return
                }
                else -> return
            }

            val mimeType = getMimeType(filePath ?: "video.mp4")
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share video").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun hasEnoughStorageSpace(context: Context, requiredBytes: Long = 50 * 1024 * 1024L): Boolean {
        return try {
            val stat = android.os.StatFs(context.cacheDir.path)
            val available = stat.availableBlocksLong * stat.blockSizeLong
            available >= requiredBytes
        } catch (_: Exception) {
            true
        }
    }
}
