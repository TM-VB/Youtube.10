package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.domain.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadTaskDao {

    @Query("SELECT * FROM download_tasks ORDER BY queueOrder ASC, createdAt DESC")
    fun getAllTasks(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE id = :id")
    fun getTaskById(id: String): Flow<DownloadTaskEntity?>

    @Query("SELECT * FROM download_tasks WHERE id = :id")
    suspend fun getTaskByIdSync(id: String): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE status = 'QUEUED' ORDER BY queueOrder ASC, createdAt ASC")
    suspend fun getQueuedTasks(): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE status = 'DOWNLOADING' OR status = 'PREPARING' OR status = 'PROCESSING_FFMPEG'")
    suspend fun getActiveTasksSync(): List<DownloadTaskEntity>

    @Query("UPDATE download_tasks SET status = 'INTERRUPTED', downloadSpeed = '', eta = '' WHERE status = 'DOWNLOADING' OR status = 'PREPARING' OR status = 'PROCESSING_FFMPEG'")
    suspend fun markActiveTasksAsInterrupted(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: DownloadTaskEntity)

    @Update
    suspend fun updateTask(task: DownloadTaskEntity)

    @Query("UPDATE download_tasks SET status = :status, progress = :progress, downloadSpeed = :downloadSpeed, eta = :eta WHERE id = :id")
    suspend fun updateProgress(id: String, status: DownloadStatus, progress: Float, downloadSpeed: String, eta: String)

    @Query("SELECT * FROM download_tasks WHERE url = :url AND formatId = :formatId AND (((startTime IS NULL OR startTime = '') AND (:startTime IS NULL OR :startTime = '')) OR startTime = :startTime) AND (((endTime IS NULL OR endTime = '') AND (:endTime IS NULL OR :endTime = '')) OR endTime = :endTime) LIMIT 1")
    suspend fun findExistingTask(url: String, formatId: String, startTime: String?, endTime: String?): DownloadTaskEntity?

    @Query("UPDATE download_tasks SET queueOrder = :order WHERE id = :id")
    suspend fun updateQueueOrder(id: String, order: Long)

    @Query("DELETE FROM download_tasks WHERE id IN (:ids)")
    suspend fun deleteTasksByIds(ids: List<String>)

    @Query("SELECT * FROM download_tasks WHERE status = 'COMPLETED'")
    suspend fun getAllCompletedTasksSync(): List<DownloadTaskEntity>

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun deleteTaskById(id: String)

    @Query("DELETE FROM download_tasks WHERE status = 'COMPLETED' OR status = 'CANCELLED' OR status = 'FAILED' OR status = 'INTERRUPTED'")
    suspend fun clearFinishedTasks()
}
