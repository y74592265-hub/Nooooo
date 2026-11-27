package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.domain.model.ClipContentType
import com.example.domain.model.ClipSyncState
import com.example.domain.model.SyncProviderId
import com.example.domain.model.SyncQueueStatus
import kotlinx.coroutines.flow.Flow

data class StorageAggregateRow(
  val totalCount: Int,
  val activeCount: Int,
  val trashedCount: Int,
  val sensitiveCount: Int,
  val pendingSyncCount: Int,
  val failedSyncCount: Int,
  val totalContentBytes: Long,
  val trashedBytes: Long,
)

data class QueueStatusCountRow(
  val status: SyncQueueStatus,
  val itemCount: Int,
)

@Dao
interface ClipDao {
  @Query("SELECT * FROM clips WHERE isTrashed = 0 ORDER BY isPinned DESC, updatedAt DESC")
  fun observeActiveClips(): Flow<List<ClipEntity>>

  @Query("SELECT * FROM clips WHERE isTrashed = 0 ORDER BY isPinned DESC, updatedAt DESC LIMIT :limit")
  fun observeActiveClipsPaged(limit: Int): Flow<List<ClipEntity>>

  @Query("SELECT COUNT(*) FROM clips WHERE isTrashed = 0")
  fun observeTotalActiveCount(): Flow<Int>

  @Query("SELECT * FROM clips WHERE isTrashed = 0 AND isFavorite = 1 ORDER BY isPinned DESC, updatedAt DESC")
  fun observeFavoriteClips(): Flow<List<ClipEntity>>

  @Query("SELECT * FROM clips WHERE isTrashed = 1 ORDER BY trashedAt DESC")
  fun observeTrashedClips(): Flow<List<ClipEntity>>

  @Query("SELECT * FROM clips WHERE isTrashed = 0 AND categoryId = :categoryId ORDER BY isPinned DESC, updatedAt DESC")
  fun observeClipsByCategory(categoryId: Long): Flow<List<ClipEntity>>

  @Query(
    "SELECT * FROM clips WHERE isTrashed = 0 AND categoryId = :categoryId ORDER BY isPinned DESC, updatedAt DESC LIMIT :limit"
  )
  fun observeClipsByCategoryPaged(categoryId: Long, limit: Int): Flow<List<ClipEntity>>

  @Query("SELECT * FROM clips WHERE id = :id LIMIT 1")
  fun observeClipById(id: Long): Flow<ClipEntity?>

  @Query("SELECT * FROM clips WHERE id = :id LIMIT 1")
  suspend fun getClipById(id: Long): ClipEntity?

  @Query("SELECT * FROM clips WHERE contentHash = :hash AND isTrashed = 0 LIMIT 1")
  suspend fun findActiveByHash(hash: String): ClipEntity?

  @Query("SELECT * FROM clips WHERE remoteId = :remoteId LIMIT 1")
  suspend fun findByRemoteId(remoteId: String): ClipEntity?

  @Query("SELECT * FROM clips WHERE isTrashed = 0 ORDER BY updatedAt DESC")
  suspend fun getAllActiveClipsOnce(): List<ClipEntity>

  @Query("SELECT * FROM clips WHERE isTrashed = 0 ORDER BY isPinned DESC, updatedAt DESC LIMIT :limit OFFSET :offset")
  suspend fun getActiveClipsPaged(limit: Int, offset: Int): List<ClipEntity>

  @Query("SELECT * FROM clips ORDER BY updatedAt DESC LIMIT :limit OFFSET :offset")
  suspend fun getClipsPaged(limit: Int, offset: Int): List<ClipEntity>

  @Query(
    """
    SELECT clips.* FROM clips
    JOIN clips_fts ON clips.id = clips_fts.rowid
    WHERE clips.isTrashed = 0
      AND clips_fts MATCH :ftsQuery
      AND (:categoryId IS NULL OR clips.categoryId = :categoryId)
      AND (:contentType IS NULL OR clips.contentType = :contentType)
      AND clips.updatedAt >= :minTimestamp
      AND (:favoritesOnly = 0 OR clips.isFavorite = 1)
    ORDER BY clips.isPinned DESC, clips.updatedAt DESC
    LIMIT :limit
    """
  )
  suspend fun searchFtsFiltered(
    ftsQuery: String,
    categoryId: Long?,
    contentType: ClipContentType?,
    minTimestamp: Long,
    favoritesOnly: Int,
    limit: Int,
  ): List<ClipEntity>

  @Query(
    """
    SELECT * FROM clips
    WHERE isTrashed = 0
      AND (:categoryId IS NULL OR categoryId = :categoryId)
      AND (:contentType IS NULL OR contentType = :contentType)
      AND updatedAt >= :minTimestamp
      AND (:favoritesOnly = 0 OR isFavorite = 1)
    ORDER BY isPinned DESC, updatedAt DESC
    LIMIT :limit
    """
  )
  suspend fun filterActiveClipsIndexed(
    categoryId: Long?,
    contentType: ClipContentType?,
    minTimestamp: Long,
    favoritesOnly: Int,
    limit: Int,
  ): List<ClipEntity>

  @Query(
    """
    SELECT * FROM clips
    WHERE isTrashed = 0
      AND isSensitive = 1
      AND (:categoryId IS NULL OR categoryId = :categoryId)
      AND (:contentType IS NULL OR contentType = :contentType)
      AND updatedAt >= :minTimestamp
      AND (:favoritesOnly = 0 OR isFavorite = 1)
    ORDER BY updatedAt DESC
    LIMIT 200
    """
  )
  suspend fun getSensitiveActiveClipsFiltered(
    categoryId: Long?,
    contentType: ClipContentType?,
    minTimestamp: Long,
    favoritesOnly: Int,
  ): List<ClipEntity>

  @Query("SELECT * FROM clips WHERE isTrashed = 0 ORDER BY contentSizeBytes DESC, updatedAt DESC LIMIT :limit")
  suspend fun getLargestClips(limit: Int): List<ClipEntity>

  @Query(
    """
    SELECT
      COUNT(*) AS totalCount,
      COALESCE(SUM(CASE WHEN isTrashed = 0 THEN 1 ELSE 0 END), 0) AS activeCount,
      COALESCE(SUM(CASE WHEN isTrashed = 1 THEN 1 ELSE 0 END), 0) AS trashedCount,
      COALESCE(SUM(CASE WHEN isTrashed = 0 AND isSensitive = 1 THEN 1 ELSE 0 END), 0) AS sensitiveCount,
      COALESCE(SUM(CASE WHEN isTrashed = 0 AND syncStatus IN ('PENDING', 'UPLOADING') THEN 1 ELSE 0 END), 0) AS pendingSyncCount,
      COALESCE(SUM(CASE WHEN isTrashed = 0 AND syncStatus IN ('FAILED', 'CONFLICT') THEN 1 ELSE 0 END), 0) AS failedSyncCount,
      COALESCE(SUM(contentSizeBytes), 0) AS totalContentBytes,
      COALESCE(SUM(CASE WHEN isTrashed = 1 THEN contentSizeBytes ELSE 0 END), 0) AS trashedBytes
    FROM clips
    """
  )
  suspend fun getStorageAggregateSnapshot(): StorageAggregateRow

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertClip(clip: ClipEntity): Long

  @Update
  suspend fun updateClip(clip: ClipEntity)

  @Query("UPDATE clips SET isPinned = :pinned, updatedAt = :updatedAt WHERE id = :id")
  suspend fun setPinned(id: Long, pinned: Boolean, updatedAt: Long)

  @Query("UPDATE clips SET isFavorite = :favorite, updatedAt = :updatedAt WHERE id = :id")
  suspend fun setFavorite(id: Long, favorite: Boolean, updatedAt: Long)

  @Query("UPDATE clips SET remoteId = :remoteId WHERE id = :id")
  suspend fun updateRemoteId(id: Long, remoteId: String)

  @Query(
    """
    UPDATE clips
    SET syncStatus = :syncStatus,
        syncError = :syncError,
        lastSyncedAt = COALESCE(:syncedAt, lastSyncedAt),
        telegramMessageId = COALESCE(:telegramMsgId, telegramMessageId),
        discordMessageId = COALESCE(:discordMsgId, discordMessageId),
        driveRevisionId = COALESCE(:driveRevId, driveRevisionId),
        remoteId = COALESCE(:remoteId, remoteId)
    WHERE id = :id
    """
  )
  suspend fun updateSyncMetadata(
    id: Long,
    syncStatus: ClipSyncState,
    syncError: String?,
    syncedAt: Long?,
    telegramMsgId: String?,
    discordMsgId: String?,
    driveRevId: String?,
    remoteId: String?,
  )

  @Query("UPDATE clips SET syncStatus = :syncStatus, syncError = :syncError WHERE id = :id")
  suspend fun updateSyncStatus(id: Long, syncStatus: ClipSyncState, syncError: String?)

  @Query("UPDATE clips SET categoryId = :categoryId, updatedAt = :updatedAt WHERE id IN (:ids)")
  suspend fun moveClipsToCategory(ids: List<Long>, categoryId: Long, updatedAt: Long)

  @Query("UPDATE clips SET isTrashed = 1, trashedAt = :trashedAt, isPinned = 0 WHERE id IN (:ids)")
  suspend fun softDeleteClips(ids: List<Long>, trashedAt: Long)

  @Query("UPDATE clips SET isTrashed = 0, trashedAt = NULL, updatedAt = :updatedAt WHERE id IN (:ids)")
  suspend fun restoreClips(ids: List<Long>, updatedAt: Long)

  @Query("DELETE FROM clips WHERE id IN (:ids)")
  suspend fun deleteClipsForever(ids: List<Long>)

  @Query("DELETE FROM clips WHERE isTrashed = 1")
  suspend fun emptyTrash()

  @Query("DELETE FROM clips WHERE isTrashed = 0 AND isPinned = 0 AND isFavorite = 0 AND updatedAt < :cutoffTimestamp")
  suspend fun purgeExpiredHistory(cutoffTimestamp: Long): Int

  @Query("DELETE FROM clips")
  suspend fun deleteAllClips()

  /**
   * SQL-level privacy boundary for IME: strictly excludes trashed & sensitive rows,
   * and reads only a 180-char preview projection so sensitive ciphertext never touches IME memory.
   */
  @Query(
    """
    SELECT id, title, SUBSTR(content, 1, 180) AS preview, contentType, categoryId,
           isPinned, isFavorite, useCount, lastPastedAt, updatedAt, versionNumber
    FROM clips
    WHERE isTrashed = 0 AND isSensitive = 0
    ORDER BY isPinned DESC, useCount DESC, updatedAt DESC
    LIMIT :limit
    """
  )
  suspend fun getImeCandidateProjections(limit: Int): List<ImeClipProjectionRow>

  @Query("SELECT content FROM clips WHERE id = :id AND isTrashed = 0 AND isSensitive = 0 LIMIT 1")
  suspend fun getPasteableContentById(id: Long): String?

  @Query(
    """
    SELECT * FROM clips
    WHERE isTrashed = 0
      AND isSensitive = 0
      AND contentType = :contentType
      AND updatedAt >= :minUpdatedAt
    ORDER BY updatedAt DESC
    LIMIT :limit
    """
  )
  suspend fun findLineageCandidates(
    contentType: ClipContentType,
    minUpdatedAt: Long,
    limit: Int = 20,
  ): List<ClipEntity>

  @Query("UPDATE clips SET useCount = useCount + 1, lastPastedAt = :nowMs WHERE id = :id AND isSensitive = 0")
  suspend fun incrementUsage(id: Long, nowMs: Long)

  @Query("UPDATE clips SET useCount = 0, lastPastedAt = NULL")
  suspend fun resetAllUsageStats()

  @Query("UPDATE clips SET parentClipId = NULL, versionNumber = 1, simHash = 0")
  suspend fun clearAllLineageMetadata()
}

@Dao
interface SyncQueueDao {
  @Query("SELECT status, COUNT(*) AS itemCount FROM sync_queue GROUP BY status")
  fun observeQueueSummary(): Flow<List<QueueStatusCountRow>>

  @Query("SELECT status, COUNT(*) AS itemCount FROM sync_queue WHERE provider = :provider GROUP BY status")
  fun observeQueueSummaryForProvider(provider: SyncProviderId): Flow<List<QueueStatusCountRow>>

  @Query("SELECT * FROM sync_queue ORDER BY updatedAt DESC LIMIT 200")
  fun observeAllJobs(): Flow<List<SyncQueueEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertJob(item: SyncQueueEntity): Long

  @Query(
    """
    SELECT * FROM sync_queue
    WHERE provider = :provider
      AND status IN ('PENDING', 'FAILED')
      AND nextRetryAt <= :now
    ORDER BY createdAt ASC
    LIMIT :limit
    """
  )
  suspend fun getDueJobs(provider: SyncProviderId, now: Long, limit: Int): List<SyncQueueEntity>

  @Query(
    """
    SELECT * FROM sync_queue
    WHERE status IN ('PENDING', 'FAILED')
      AND nextRetryAt <= :now
    ORDER BY createdAt ASC
    LIMIT :limit
    """
  )
  suspend fun getAllDueJobs(now: Long, limit: Int): List<SyncQueueEntity>

  @Query(
    "SELECT * FROM sync_queue WHERE status IN ('PENDING', 'UPLOADING', 'FAILED')"
  )
  fun observeActiveQueueJobs(): Flow<List<SyncQueueEntity>>

  @Query(
    "UPDATE sync_queue SET status = 'PENDING', nextRetryAt = 0, updatedAt = :now WHERE status = 'FAILED' AND (:provider IS NULL OR provider = :provider)"
  )
  suspend fun requeueFailedJobs(provider: SyncProviderId?, now: Long)

  @Query("UPDATE sync_queue SET status = 'UPLOADING', updatedAt = :now WHERE id = :id")
  suspend fun markUploading(id: Long, now: Long)

  @Query("UPDATE sync_queue SET status = 'SYNCED', lastError = NULL, updatedAt = :now WHERE id = :id")
  suspend fun markSynced(id: Long, now: Long)

  @Query(
    """
    UPDATE sync_queue
    SET status = :status,
        retryCount = :retryCount,
        nextRetryAt = :nextRetryAt,
        lastError = :error,
        updatedAt = :now
    WHERE id = :id
    """
  )
  suspend fun markFailed(
    id: Long,
    status: SyncQueueStatus,
    retryCount: Int,
    nextRetryAt: Long,
    error: String?,
    now: Long,
  )

  @Query("DELETE FROM sync_queue WHERE clipId IN (:clipIds)")
  suspend fun deleteForClips(clipIds: List<Long>)

  @Query("DELETE FROM sync_queue WHERE status = 'SYNCED'")
  suspend fun clearCompleted()

  @Query("DELETE FROM sync_queue")
  suspend fun clearAll()
}

data class CategoryCountRow(
  val id: Long,
  val name: String,
  val colorHex: Long,
  val iconKey: String,
  val isDefault: Boolean,
  val sortOrder: Int,
  val clipCount: Int,
)

@Dao
interface CategoryDao {
  @Query(
    """
    SELECT c.id, c.name, c.colorHex, c.iconKey, c.isDefault, c.sortOrder,
           COUNT(cl.id) AS clipCount
    FROM categories c
    LEFT JOIN clips cl ON c.id = cl.categoryId AND cl.isTrashed = 0
    GROUP BY c.id
    ORDER BY c.sortOrder ASC, c.id ASC
    """
  )
  fun observeCategoriesWithCount(): Flow<List<CategoryCountRow>>

  @Query("SELECT * FROM categories ORDER BY sortOrder ASC, id ASC")
  suspend fun getAllCategoriesOnce(): List<CategoryEntity>

  @Query("SELECT * FROM categories WHERE LOWER(name) = LOWER(:name) LIMIT 1")
  suspend fun findByName(name: String): CategoryEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertCategory(category: CategoryEntity): Long

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertCategories(categories: List<CategoryEntity>)

  @Update
  suspend fun updateCategory(category: CategoryEntity)

  @Query("DELETE FROM categories WHERE id = :id AND isDefault = 0")
  suspend fun deleteCustomCategory(id: Long)

  @Query("UPDATE clips SET categoryId = 1 WHERE categoryId = :deletedCategoryId")
  suspend fun reassignClipsToPersonal(deletedCategoryId: Long)
}

@Dao
interface SyncLogDao {
  @Query("SELECT * FROM sync_logs ORDER BY timestamp DESC LIMIT 100")
  fun observeRecentLogs(): Flow<List<SyncLogEntity>>

  @Insert
  suspend fun insertLog(log: SyncLogEntity): Long

  @Query("DELETE FROM sync_logs")
  suspend fun clearLogs()
}
