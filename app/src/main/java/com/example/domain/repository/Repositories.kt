package com.example.domain.repository

import com.example.domain.model.Category
import com.example.domain.model.ClipContentType
import com.example.domain.model.ClipItem
import com.example.domain.model.ClipSource
import com.example.domain.model.SyncLog
import kotlinx.coroutines.flow.Flow

data class SaveClipResult(
  val clipId: Long,
  val wasDuplicateMerged: Boolean,
)

interface ClipRepository {
  fun observeActiveClips(): Flow<List<ClipItem>>

  fun observeFavoriteClips(): Flow<List<ClipItem>>

  fun observeTrashedClips(): Flow<List<ClipItem>>

  fun observeClipsByCategory(categoryId: Long): Flow<List<ClipItem>>

  fun observeClipById(id: Long): Flow<ClipItem?>

  suspend fun getClipById(id: Long): ClipItem?

  suspend fun getAllActiveClipsOnce(): List<ClipItem>

  suspend fun findByRemoteId(remoteId: String): ClipItem?

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
  ): SaveClipResult

  suspend fun updateClip(clip: ClipItem)

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
