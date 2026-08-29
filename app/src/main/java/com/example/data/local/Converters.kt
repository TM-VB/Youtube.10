package com.example.data.local

import androidx.room.TypeConverter
import com.example.domain.model.DownloadStatus

class Converters {
    @TypeConverter
    fun fromStatus(status: DownloadStatus?): String? {
        return status?.name
    }

    @TypeConverter
    fun toStatus(value: String?): DownloadStatus {
        return try {
            if (value != null) DownloadStatus.valueOf(value) else DownloadStatus.QUEUED
        } catch (_: Exception) {
            DownloadStatus.QUEUED
        }
    }
}
