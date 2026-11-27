package com.example.domain.repository

import com.example.domain.model.Category
import com.example.domain.model.ClipContentType
import com.example.domain.model.ClipItem
import com.example.domain.model.ClipSource
import com.example.domain.model.ClipSyncState
import com.example.domain.model.QueueSummary
import com.example.domain.model.SyncLog
import com.example.domain.model.SyncProviderId
import com.example.domain.model.SyncQueueItem
import com.example.domain.model.SyncQueueOperation
import com.example.domain.model.SyncQueueStatus
import com.example.domain.usecase.SearchFilter
import kotlinx.coroutines.flow.Flow

data class SaveClipResult(
  val clipId: Long,
  val wasDuplicateMerged: Boolean,
  val wasLineageVersioned: Boolean = false,
  val newVersionNumber: Int = 1,
)

data class StorageAggregateSnapshot(
  val totalCount: Int,
  val activeCount: Int,
  val trashedCount: Int,
  val sensitiveCount: Int,
  val pendingSyncCount: Int,
  val failedSyncCount: Int,
  val totalContentBytes: Long,
  val trashedBytes: Long,
)

interface ClipRepository {
  fun observeActiveClips(): Flow<List<ClipItem>>

  fun observeActiveClipsPaged(limit: Int): Flow<List<ClipItem>>

  fun observeTotalActiveCount(): Flow<Int>

  fun observeFavoriteClips(): Flow<List<ClipItem>>

  fun observeTrashedClips(): Flow<List<ClipItem>>

  fun observeClipsByCategory(categoryId: Long): Flow<List<ClipItem>>

  fun observeClipsByCategoryPaged(categoryId: Long, limit: Int): Flow<List<ClipItem>>

  fun observeClipById(id: Long): Flow<ClipItem?>

  suspend fun getClipById(id: Long): ClipItem?

  suspend fun getAllActiveClipsOnce(): List<ClipItem>

  suspend fun getActiveClipsPaged(limit: Int, offset: Int): List<ClipItem>

  suspend fun searchActiveClipsFts(filter: SearchFilter, limit: Int = 200): List<ClipItem>

  suspend fun getLargestClips(limit: Int = 20): List<ClipItem>

  suspend fun getStorageAggregateSnapshot(): StorageAggregateSnapshot

  suspend fun findByRemoteId(remoteId: String): ClipItem?

  suspend fun findActiveByHash(hash: String): ClipItem?

  suspend fun saveNewOrDeduplicate(
    title: String,
    content: String,
    contentType: ClipContentType? = null,
    categoryId: Long? = null,
    tags: List<String> = emptyList(),
    isPinned: Boolean = false,
    isFavorite: Boolean = false,
    isSensitive: Boolean = false,
    source: ClipSource = ClipSource.MANUAL,
    remoteId: String? = null,
    syncStatus: ClipSyncState? = null,
    updatedAtOverride: Long? = null,
  ): SaveClipResult

  suspend fun updateClip(clip: ClipItem)

  suspend fun updateRemoteId(id: Long, remoteId: String)

  suspend fun updateProviderSyncMetadata(
    clipId: Long,
    providerId: SyncProviderId,
    remoteMessageOrFileId: String?,
    syncStatus: ClipSyncState,
    syncedAt: Long = System.currentTimeMillis(),
    syncError: String? = null,
  )

  suspend fun updateClipSyncStatus(clipId: Long, syncStatus: ClipSyncState, syncError: String? = null)

  suspend fun setPinned(id: Long, pinned: Boolean)

  suspend fun setFavorite(id: Long, favorite: Boolean)

  suspend fun moveClipsToCategory(ids: List<Long>, categoryId: Long)

  suspend fun moveToTrash(ids: List<Long>)

  suspend fun restoreFromTrash(ids: List<Long>)

  suspend fun deleteForever(ids: List<Long>)

  suspend fun emptyTrash()

  suspend fun purgeExpiredHistory(retentionDays: Int): Int

  suspend fun wipeAllData()
}

interface SyncQueueRepository {
  fun observeQueueSummary(): Flow<QueueSummary>

  fun observeQueueSummaryForProvider(providerId: SyncProviderId): Flow<QueueSummary>

  fun observeAllJobs(): Flow<List<SyncQueueItem>>

  suspend fun enqueueClip(
    clipId: Long,
    providerId: SyncProviderId,
    operation: SyncQueueOperation = SyncQueueOperation.PUSH,
    remoteTargetId: String? = null,
  )

  suspend fun getDueJobs(providerId: SyncProviderId, now: Long = System.currentTimeMillis(), limit: Int = 25): List<SyncQueueItem>

  suspend fun getAllDueJobs(now: Long = System.currentTimeMillis(), limit: Int = 60): List<SyncQueueItem>

  suspend fun requeueFailedJobs(providerId: SyncProviderId? = null)

  suspend fun markUploading(jobId: Long)

  suspend fun markSynced(jobId: Long)

  suspend fun markFailed(
    jobId: Long,
    retryCount: Int,
    nextRetryAt: Long,
    error: String?,
    status: SyncQueueStatus = SyncQueueStatus.FAILED,
  )

  suspend fun deleteJobsForClips(clipIds: List<Long>)

  suspend fun clearCompleted()

  suspend fun clearAll()
}

interface CategoryRepository {
  fun observeCategories(): Flow<List<Category>>

  suspend fun ensureDefaultCategories()

  suspend fun getAllCategoriesOnce(): List<Category>

  suspend fun createCategory(name: String, colorHex: Long, iconKey: String = "folder"): Long

  suspend fun renameCategory(id: Long, newName: String, newColorHex: Long? = null)

  suspend fun deleteCustomCategory(id: Long)
}

interface SyncLogRepository {
  fun observeRecentLogs(): Flow<List<SyncLog>>

  suspend fun addLog(log: SyncLog)

  suspend fun clearLogs()
}
