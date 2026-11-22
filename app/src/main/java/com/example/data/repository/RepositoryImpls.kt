package com.example.data.repository

import com.example.core.clipboard.SystemClipboardHelper
import com.example.core.security.KeystoreCryptoManager
import com.example.data.local.CategoryDao
import com.example.data.local.CategoryEntity
import com.example.data.local.ClipDao
import com.example.data.local.ClipEntity
import com.example.data.local.SmartClipboardDatabase
import com.example.data.local.SyncLogDao
import com.example.data.local.SyncLogEntity
import com.example.domain.model.Category
import com.example.domain.model.CategoryDefaults
import com.example.domain.model.ClipContentType
import com.example.domain.model.ClipItem
import com.example.domain.model.ClipSource
import com.example.domain.model.SyncLog
import com.example.domain.repository.CategoryRepository
import com.example.domain.repository.ClipRepository
import com.example.domain.repository.SaveClipResult
import com.example.domain.repository.SyncLogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClipRepositoryImpl
@Inject
constructor(
  private val clipDao: ClipDao,
  private val cryptoManager: KeystoreCryptoManager,
  private val clipboardHelper: SystemClipboardHelper,
) : ClipRepository {

  override fun observeActiveClips(): Flow<List<ClipItem>> =
    clipDao.observeActiveClips().map { list -> list.map { it.toDomain() } }

  override fun observeFavoriteClips(): Flow<List<ClipItem>> =
    clipDao.observeFavoriteClips().map { list -> list.map { it.toDomain() } }

  override fun observeTrashedClips(): Flow<List<ClipItem>> =
    clipDao.observeTrashedClips().map { list -> list.map { it.toDomain() } }

  override fun observeClipsByCategory(categoryId: Long): Flow<List<ClipItem>> =
    clipDao.observeClipsByCategory(categoryId).map { list -> list.map { it.toDomain() } }

  override fun observeClipById(id: Long): Flow<ClipItem?> =
    clipDao.observeClipById(id).map { it?.toDomain() }

  override suspend fun getClipById(id: Long): ClipItem? =
    withContext(Dispatchers.IO) { clipDao.getClipById(id)?.toDomain() }

  override suspend fun getAllActiveClipsOnce(): List<ClipItem> =
    withContext(Dispatchers.IO) { clipDao.getAllActiveClipsOnce().map { it.toDomain() } }

  override suspend fun findByRemoteId(remoteId: String): ClipItem? =
    withContext(Dispatchers.IO) { clipDao.findByRemoteId(remoteId)?.toDomain() }

  override suspend fun saveNewOrDeduplicate(
    title: String,
    content: String,
    contentType: ClipContentType?,
    categoryId: Long?,
    tags: List<String>,
    isPinned: Boolean,
    isFavorite: Boolean,
    isSensitive: Boolean,
    source: ClipSource,
    remoteId: String?,
  ): SaveClipResult =
    withContext(Dispatchers.IO) {
      val trimmed = content.trim()
      val hash = cryptoManager.sha256(trimmed)
      val now = System.currentTimeMillis()
      val existing = clipDao.findActiveByHash(hash)

      if (existing != null) {
        val mergedTags =
          (existing.tagsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() } + tags)
            .distinct()
        val updated =
          existing.copy(
            title = existing.title.ifBlank { title.trim() },
            tagsCsv = mergedTags.joinToString(","),
            isPinned = existing.isPinned || isPinned,
            isFavorite = existing.isFavorite || isFavorite,
            updatedAt = now,
            remoteId = existing.remoteId ?: remoteId,
          )
        clipDao.updateClip(updated)
        return@withContext SaveClipResult(clipId = existing.id, wasDuplicateMerged = true)
      }

      val resolvedType = contentType ?: clipboardHelper.classifyContent(trimmed)
      val resolvedCategory =
        categoryId
          ?: when (resolvedType) {
            ClipContentType.LINK -> CategoryDefaults.LINKS_ID
            ClipContentType.CODE -> CategoryDefaults.PROGRAMMING_ID
            ClipContentType.NOTE -> CategoryDefaults.NOTES_ID
            else -> CategoryDefaults.PERSONAL_ID
          }

      val storedContent = if (isSensitive) cryptoManager.encryptLocal(trimmed) else trimmed
      val entity =
        ClipEntity(
          title = title.trim(),
          content = storedContent,
          contentHash = hash,
          contentType = resolvedType,
          categoryId = resolvedCategory,
          tagsCsv = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(","),
          isPinned = isPinned,
          isFavorite = isFavorite,
          isSensitive = isSensitive,
          isTrashed = false,
          trashedAt = null,
          createdAt = now,
          updatedAt = now,
          source = source,
          remoteId = remoteId,
        )
      val newId = clipDao.insertClip(entity)
      SaveClipResult(clipId = newId, wasDuplicateMerged = false)
    }

  override suspend fun updateClip(clip: ClipItem) =
    withContext(Dispatchers.IO) {
      val trimmed = clip.content.trim()
      val hash = cryptoManager.sha256(trimmed)
      val storedContent = if (clip.isSensitive) cryptoManager.encryptLocal(trimmed) else trimmed
      val entity =
        ClipEntity(
          id = clip.id,
          title = clip.title.trim(),
          content = storedContent,
          contentHash = hash,
          contentType = clip.contentType,
          categoryId = clip.categoryId,
          tagsCsv =
            clip.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(","),
          isPinned = clip.isPinned,
          isFavorite = clip.isFavorite,
          isSensitive = clip.isSensitive,
          isTrashed = clip.isTrashed,
          trashedAt = clip.trashedAt,
          createdAt = clip.createdAt,
          updatedAt = System.currentTimeMillis(),
          source = clip.source,
          remoteId = clip.remoteId,
        )
      clipDao.updateClip(entity)
    }

  override suspend fun setPinned(id: Long, pinned: Boolean) =
    withContext(Dispatchers.IO) { clipDao.setPinned(id, pinned, System.currentTimeMillis()) }

  override suspend fun setFavorite(id: Long, favorite: Boolean) =
    withContext(Dispatchers.IO) { clipDao.setFavorite(id, favorite, System.currentTimeMillis()) }

  override suspend fun moveClipsToCategory(ids: List<Long>, categoryId: Long) {
    if (ids.isEmpty()) return
    withContext(Dispatchers.IO) {
      clipDao.moveClipsToCategory(ids, categoryId, System.currentTimeMillis())
    }
  }

  override suspend fun moveToTrash(ids: List<Long>) {
    if (ids.isEmpty()) return
    withContext(Dispatchers.IO) { clipDao.softDeleteClips(ids, System.currentTimeMillis()) }
  }

  override suspend fun restoreFromTrash(ids: List<Long>) {
    if (ids.isEmpty()) return
    withContext(Dispatchers.IO) { clipDao.restoreClips(ids, System.currentTimeMillis()) }
  }

  override suspend fun deleteForever(ids: List<Long>) {
    if (ids.isEmpty()) return
    withContext(Dispatchers.IO) { clipDao.deleteClipsForever(ids) }
  }

  override suspend fun emptyTrash() = withContext(Dispatchers.IO) { clipDao.emptyTrash() }

  override suspend fun purgeExpiredHistory(retentionDays: Int): Int {
    if (retentionDays <= 0) return 0
    val cutoff = System.currentTimeMillis() - retentionDays.toLong() * 24L * 60L * 60L * 1000L
    return withContext(Dispatchers.IO) { clipDao.purgeExpiredHistory(cutoff) }
  }

  override suspend fun wipeAllData() = withContext(Dispatchers.IO) { clipDao.deleteAllClips() }

  private fun ClipEntity.toDomain(): ClipItem {
    val plain = if (isSensitive) cryptoManager.decryptLocal(content) else content
    return ClipItem(
      id = id,
      title = title,
      content = plain,
      contentHash = contentHash,
      contentType = contentType,
      categoryId = categoryId,
      tags = tagsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() },
      isPinned = isPinned,
      isFavorite = isFavorite,
      isSensitive = isSensitive,
      isTrashed = isTrashed,
      trashedAt = trashedAt,
      createdAt = createdAt,
      updatedAt = updatedAt,
      source = source,
      remoteId = remoteId,
    )
  }
}

@Singleton
class CategoryRepositoryImpl @Inject constructor(private val categoryDao: CategoryDao) :
  CategoryRepository {

  override fun observeCategories(): Flow<List<Category>> =
    categoryDao.observeCategoriesWithCount().map { rows ->
      rows.map {
        Category(
          id = it.id,
          name = it.name,
          colorHex = it.colorHex,
          iconKey = it.iconKey,
          isDefault = it.isDefault,
          sortOrder = it.sortOrder,
          clipCount = it.clipCount,
        )
      }
    }

  override suspend fun ensureDefaultCategories() =
    withContext(Dispatchers.IO) {
      if (categoryDao.getAllCategoriesOnce().isEmpty()) {
        categoryDao.insertCategories(SmartClipboardDatabase.DEFAULT_CATEGORIES)
      }
    }

  override suspend fun getAllCategoriesOnce(): List<Category> =
    withContext(Dispatchers.IO) {
      categoryDao.getAllCategoriesOnce().map {
        Category(
          id = it.id,
          name = it.name,
          colorHex = it.colorHex,
          iconKey = it.iconKey,
          isDefault = it.isDefault,
          sortOrder = it.sortOrder,
        )
      }
    }

  override suspend fun createCategory(name: String, colorHex: Long, iconKey: String): Long =
    withContext(Dispatchers.IO) {
      val trimmed = name.trim()
      val existing = categoryDao.findByName(trimmed)
      if (existing != null) return@withContext existing.id
      val currentMaxOrder = categoryDao.getAllCategoriesOnce().maxOfOrNull { it.sortOrder } ?: 6
      categoryDao.insertCategory(
        CategoryEntity(
          name = trimmed,
          colorHex = colorHex,
          iconKey = iconKey,
          isDefault = false,
          sortOrder = currentMaxOrder + 1,
        )
      )
    }

  override suspend fun renameCategory(id: Long, newName: String, newColorHex: Long?) =
    withContext(Dispatchers.IO) {
      val existing = categoryDao.getAllCategoriesOnce().find { it.id == id } ?: return@withContext
      categoryDao.updateCategory(
        existing.copy(
          name = newName.trim().ifEmpty { existing.name },
          colorHex = newColorHex ?: existing.colorHex,
        )
      )
    }

  override suspend fun deleteCustomCategory(id: Long) =
    withContext(Dispatchers.IO) {
      categoryDao.reassignClipsToPersonal(id)
      categoryDao.deleteCustomCategory(id)
    }
}

@Singleton
class SyncLogRepositoryImpl @Inject constructor(private val syncLogDao: SyncLogDao) :
  SyncLogRepository {

  override fun observeRecentLogs(): Flow<List<SyncLog>> =
    syncLogDao.observeRecentLogs().map { rows ->
      rows.map {
        SyncLog(
          id = it.id,
          provider = it.provider,
          direction = it.direction,
          status = it.status,
          message = it.message,
          itemsCount = it.itemsCount,
          timestamp = it.timestamp,
        )
      }
    }

  override suspend fun addLog(log: SyncLog) =
    withContext(Dispatchers.IO) {
      syncLogDao.insertLog(
        SyncLogEntity(
          provider = log.provider,
          direction = log.direction,
          status = log.status,
          message = log.message,
          itemsCount = log.itemsCount,
          timestamp = log.timestamp,
        )
      )
      Unit
    }

  override suspend fun clearLogs() = withContext(Dispatchers.IO) { syncLogDao.clearLogs() }
}
