package com.example.downloader.engine

import com.example.domain.model.CutMode
import com.example.domain.model.MediaResult
import com.example.domain.model.ProcessingProgress
import java.io.File

/**
 * High-level interface for media processing tasks (merging, fast cut, precise cut, remux, extraction).
 */
interface MediaProcessor {

    suspend fun mergeVideoAudio(
        videoFile: File,
        audioFile: File,
        outputFile: File,
        onProgress: ((ProcessingProgress) -> Unit)? = null
    ): Result<MediaResult>

    suspend fun fastCut(
        inputFile: File,
        startTime: String,
        endTime: String,
        outputFile: File,
        onProgress: ((ProcessingProgress) -> Unit)? = null
    ): Result<MediaResult>

    suspend fun preciseCut(
        inputFile: File,
        startTime: String,
        endTime: String,
        outputFile: File,
        onProgress: ((ProcessingProgress) -> Unit)? = null
    ): Result<MediaResult>

    suspend fun cutMedia(
        inputFile: File,
        outputFile: File,
        startTime: String,
        endTime: String,
        mode: CutMode,
        onProgress: ((ProcessingProgress) -> Unit)? = null
    ): Result<MediaResult>

    suspend fun remux(
        inputFile: File,
        outputFile: File,
        targetContainer: String,
        onProgress: ((ProcessingProgress) -> Unit)? = null
    ): Result<MediaResult>

    suspend fun extractAudio(
        inputFile: File,
        outputFile: File,
        audioCodec: String = "aac",
        onProgress: ((ProcessingProgress) -> Unit)? = null
    ): Result<MediaResult>

    fun cancel(processId: String)
}
