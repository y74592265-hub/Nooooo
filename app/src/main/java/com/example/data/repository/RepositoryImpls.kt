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
import com.example.data.local.SyncQueueDao
import com.example.data.local.SyncQueueEntity
import com.example.domain.intelligence.ArabicTextNormalizer
import com.example.domain.lineage.ClipLineageEngine
import com.example.domain.lineage.SimHash64
import com.example.domain.model.Category
import com.example.domain.model.CategoryDefaults
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
import com.example.domain.repository.CategoryRepository
import com.example.domain.repository.ClipRepository
import com.example.domain.repository.SaveClipResult
import com.example.domain.repository.StorageAggregateSnapshot
import com.example.domain.repository.SyncLogRepository
import com.example.domain.repository.SyncQueueRepository
import com.example.domain.usecase.DateFilter
import com.example.domain.usecase.SearchFilter
import com.example.domain.usecase.SearchSortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
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
    clipDao
      .observeActiveClips()
      .distinctUntilChanged()
      .map { list -> list.map { it.toDomain() } }
      .flowOn(Dispatchers.Default)

  override fun observeActiveClipsPaged(limit: Int): Flow<List<ClipItem>> =
    clipDao
      .observeActiveClipsPaged(limit.coerceAtLeast(1))
      .distinctUntilChanged()
      .map { list -> list.map { it.toDomain() } }
      .flowOn(Dispatchers.Default)

  override fun observeTotalActiveCount(): Flow<Int> =
    clipDao.observeTotalActiveCount().distinctUntilChanged()

  override fun observeFavoriteClips(): Flow<List<ClipItem>> =
    clipDao
      .observeFavoriteClips()
      .distinctUntilChanged()
      .map { list -> list.map { it.toDomain() } }
      .flowOn(Dispatchers.Default)

  override fun observeTrashedClips(): Flow<List<ClipItem>> =
    clipDao
      .observeTrashedClips()
      .distinctUntilChanged()
      .map { list -> list.map { it.toDomain() } }
      .flowOn(Dispatchers.Default)

  override fun observeClipsByCategory(categoryId: Long): Flow<List<ClipItem>> =
    clipDao
      .observeClipsByCategory(categoryId)
      .distinctUntilChanged()
      .map { list -> list.map { it.toDomain() } }
      .flowOn(Dispatchers.Default)

  override fun observeClipsByCategoryPaged(categoryId: Long, limit: Int): Flow<List<ClipItem>> =
    clipDao
      .observeClipsByCategoryPaged(categoryId, limit.coerceAtLeast(1))
      .distinctUntilChanged()
      .map { list -> list.map { it.toDomain() } }
      .flowOn(Dispatchers.Default)

  override fun observeClipById(id: Long): Flow<ClipItem?> =
    clipDao.observeClipById(id).distinctUntilChanged().map { it?.toDomain() }.flowOn(Dispatchers.Default)

  override suspend fun getClipById(id: Long): ClipItem? =
    withContext(Dispatchers.IO) { clipDao.getClipById(id)?.toDomain() }

  override suspend fun getAllActiveClipsOnce(): List<ClipItem> =
    withContext(Dispatchers.IO) { clipDao.getAllActiveClipsOnce().map { it.toDomain() } }

  override suspend fun getActiveClipsPaged(limit: Int, offset: Int): List<ClipItem> =
    withContext(Dispatchers.IO) { clipDao.getActiveClipsPaged(limit, offset).map { it.toDomain() } }

  override suspend fun searchActiveClipsFts(filter: SearchFilter, limit: Int): List<ClipItem> =
    withContext(Dispatchers.IO) {
      val now = System.currentTimeMillis()
      val minTimestamp =
        when (filter.dateFilter) {
          DateFilter.ALL -> 0L
          DateFilter.TODAY -> now - 24L * 60L * 60L * 1000L
          DateFilter.LAST_7_DAYS -> now - 7L * 24L * 60L * 60L * 1000L
          DateFilter.LAST_30_DAYS -> now - 30L * 24L * 60L * 60L * 1000L
        }
      val favFlag = if (filter.favoritesOnly) 1 else 0
      val rawQuery = filter.query.trim()

      val entities =
        if (rawQuery.isEmpty()) {
          clipDao.filterActiveClipsIndexed(
            categoryId = filter.categoryId,
            contentType = filter.contentType,
            minTimestamp = minTimestamp,
            favoritesOnly = favFlag,
            limit = limit,
          )
        } else {
          val ftsExpr = buildFtsPrefixExpression(rawQuery)
          val ftsMatches =
            if (ftsExpr.isNotEmpty()) {
              runCatching {
                  clipDao.searchFtsFiltered(
                    ftsQuery = ftsExpr,
                    categoryId = filter.categoryId,
                    contentType = filter.contentType,
                    minTimestamp = minTimestamp,
                    favoritesOnly = favFlag,
                    limit = limit,
                  )
                }
                .getOrDefault(emptyList())
            } else {
              emptyList()
            }

          // Also support Arabic orthography variants (أ/إ/آ->ا, ة->ه, ى->ي, tashkeel) & camelCase
          val normalizedFallback =
            if (ftsMatches.size < limit) {
              clipDao
                .filterActiveClipsIndexed(
                  categoryId = filter.categoryId,
                  contentType = filter.contentType,
                  minTimestamp = minTimestamp,
                  favoritesOnly = favFlag,
                  limit = limit,
                )
                .asSequence()
                .filter { !it.isSensitive }
                .map { it.toDomain() }
                .filter { clip ->
                  ArabicTextNormalizer.normalizedContains(clip.title, rawQuery) ||
                    ArabicTextNormalizer.normalizedContains(clip.content, rawQuery) ||
                    clip.tags.any { ArabicTextNormalizer.normalizedContains(it, rawQuery) }
                }
                .toList()
            } else {
              emptyList()
            }

          // Sensitive clips store encrypted text in DB; check decrypted sensitive subset separately in memory
          val sensitiveMatches =
            clipDao
              .getSensitiveActiveClipsFiltered(
                categoryId = filter.categoryId,
                contentType = filter.contentType,
                minTimestamp = minTimestamp,
                favoritesOnly = favFlag,
              )
              .map { it.toDomain() }
              .filter { clip ->
                ArabicTextNormalizer.normalizedContains(clip.title, rawQuery) ||
                  ArabicTextNormalizer.normalizedContains(clip.content, rawQuery) ||
                  clip.tags.any { ArabicTextNormalizer.normalizedContains(it, rawQuery) }
              }

          val seenIds =
            HashSet<Long>(ftsMatches.size + normalizedFallback.size + sensitiveMatches.size)
          val merged =
            ArrayList<ClipItem>(ftsMatches.size + normalizedFallback.size + sensitiveMatches.size)
          for (e in ftsMatches) {
            val domain = e.toDomain()
            if (seenIds.add(domain.id)) merged.add(domain)
          }
          for (n in normalizedFallback) {
            if (seenIds.add(n.id)) merged.add(n)
          }
          for (s in sensitiveMatches) {
            if (seenIds.add(s.id)) merged.add(s)
          }
          return@withContext sortDomainResults(merged, rawQuery, filter.sortOrder)
        }

      val mapped = entities.map { it.toDomain() }
      sortDomainResults(mapped, rawQuery, filter.sortOrder)
    }

  private fun buildFtsPrefixExpression(raw: String): String {
    val tokens =
      raw
        .split(Regex("""\s+"""))
        .map { tok -> tok.replace(Regex("""["'*():^~]"""), "").trim() }
        .filter { it.isNotEmpty() }
        .take(6)
    if (tokens.isEmpty()) return ""
    return tokens.joinToString(" ") { "\"$it\"*" }
  }

  private fun sortDomainResults(
    items: List<ClipItem>,
    query: String,
    order: SearchSortOrder,
  ): List<ClipItem> =
    when (order) {
      SearchSortOrder.NEWEST ->
        items.sortedWith(compareByDescending<ClipItem> { it.isPinned }.thenByDescending { it.updatedAt })
      SearchSortOrder.OLDEST -> items.sortedBy { it.updatedAt }
      SearchSortOrder.RELEVANCE ->
        if (query.isEmpty()) {
          items.sortedWith(compareByDescending<ClipItem> { it.isPinned }.thenByDescending { it.updatedAt })
        } else {
          items.sortedWith(
            compareByDescending<ClipItem> {
              var score = 0
              if (ArabicTextNormalizer.normalizedContains(it.title, query)) score += 60
              if (it.tags.any { t -> ArabicTextNormalizer.normalizedContains(t, query) }) score += 40
              if (ArabicTextNormalizer.normalizedContains(it.content, query)) score += 20
              if (it.isPinned) score += 10
              score
            }
              .thenByDescending { it.updatedAt }
          )
        }
    }

  override suspend fun getLargestClips(limit: Int): List<ClipItem> =
    withContext(Dispatchers.IO) { clipDao.getLargestClips(limit).map { it.toDomain() } }

  override suspend fun getStorageAggregateSnapshot(): StorageAggregateSnapshot =
    withContext(Dispatchers.IO) {
      val row = clipDao.getStorageAggregateSnapshot()
      StorageAggregateSnapshot(
        totalCount = row.totalCount,
        activeCount = row.activeCount,
        trashedCount = row.trashedCount,
        sensitiveCount = row.sensitiveCount,
        pendingSyncCount = row.pendingSyncCount,
        failedSyncCount = row.failedSyncCount,
        totalContentBytes = row.totalContentBytes,
        trashedBytes = row.trashedBytes,
      )
    }

  override suspend fun findByRemoteId(remoteId: String): ClipItem? =
    withContext(Dispatchers.IO) { clipDao.findByRemoteId(remoteId)?.toDomain() }

  override suspend fun findActiveByHash(hash: String): ClipItem? =
    withContext(Dispatchers.IO) { clipDao.findActiveByHash(hash)?.toDomain() }

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
    syncStatus: ClipSyncState?,
    updatedAtOverride: Long?,
  ): SaveClipResult =
    withContext(Dispatchers.IO) {
      val trimmed = content.trim()
      val hash = cryptoManager.sha256(trimmed)
      val now = updatedAtOverride ?: System.currentTimeMillis()
      val existing = clipDao.findActiveByHash(hash)

      if (existing != null) {
        val mergedTags =
          (existing.tagsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() } + tags)
            .distinct()
        val resolvedRemote = existing.remoteId ?: remoteId
        val nextSyncStatus =
          when {
            existing.isSensitive || isSensitive -> ClipSyncState.LOCAL_ONLY
            syncStatus != null -> syncStatus
            resolvedRemote != null -> ClipSyncState.SYNCED
            else -> existing.syncStatus
          }
        val updated =
          existing.copy(
            title = existing.title.ifBlank { title.trim() },
            tagsCsv = mergedTags.joinToString(","),
            isPinned = existing.isPinned || isPinned,
            isFavorite = existing.isFavorite || isFavorite,
            isSensitive = existing.isSensitive || isSensitive,
            updatedAt = maxOf(existing.updatedAt, now),
            remoteId = resolvedRemote,
            syncStatus = nextSyncStatus,
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
      val byteSize = trimmed.toByteArray(StandardCharsets.UTF_8).size.toLong()
      val initialSync =
        syncStatus
          ?: when {
            isSensitive -> ClipSyncState.LOCAL_ONLY
            remoteId != null -> ClipSyncState.SYNCED
            else -> ClipSyncState.LOCAL_ONLY
          }

      val computedSimHash =
        if (!isSensitive && resolvedType != ClipContentType.LINK && trimmed.length >= ClipLineageEngine.MIN_ELIGIBLE_CHARS) {
          SimHash64.compute(trimmed)
        } else {
          0L
        }

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
          syncStatus = initialSync,
          contentSizeBytes = byteSize,
          telegramMessageId =
            remoteId?.takeIf { it.startsWith("tg:") },
          discordMessageId =
            remoteId?.takeIf { it.startsWith("discord:") },
          driveRevisionId =
            remoteId?.takeIf { it.startsWith("drive:") },
          lastSyncedAt = if (initialSync == ClipSyncState.SYNCED) now else null,
          simHash = computedSimHash,
        )
      val newId = clipDao.insertClip(entity)
      SaveClipResult(clipId = newId, wasDuplicateMerged = false)
    }

  override suspend fun updateClip(clip: ClipItem) =
    withContext(Dispatchers.IO) {
      val trimmed = clip.content.trim()
      val hash = cryptoManager.sha256(trimmed)
      val storedContent = if (clip.isSensitive) cryptoManager.encryptLocal(trimmed) else trimmed
      val byteSize = trimmed.toByteArray(StandardCharsets.UTF_8).size.toLong()
      val nextSimHash =
        if (!clip.isSensitive && clip.contentType != ClipContentType.LINK && trimmed.length >= ClipLineageEngine.MIN_ELIGIBLE_CHARS) {
          SimHash64.compute(trimmed)
        } else {
          0L
        }
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
          syncStatus = if (clip.isSensitive) ClipSyncState.LOCAL_ONLY else clip.syncStatus,
          contentSizeBytes = byteSize,
          telegramMessageId = clip.telegramMessageId,
          discordMessageId = clip.discordMessageId,
          driveRevisionId = clip.driveRevisionId,
          lastSyncedAt = clip.lastSyncedAt,
          syncError = clip.syncError,
          parentClipId = clip.parentClipId,
          versionNumber = clip.versionNumber,
          simHash = nextSimHash,
          useCount = clip.useCount,
          lastPastedAt = clip.lastPastedAt,
        )
      clipDao.updateClip(entity)
    }

  override suspend fun updateRemoteId(id: Long, remoteId: String) =
    withContext(Dispatchers.IO) { clipDao.updateRemoteId(id, remoteId) }

  override suspend fun updateProviderSyncMetadata(
    clipId: Long,
    providerId: SyncProviderId,
    remoteMessageOrFileId: String?,
    syncStatus: ClipSyncState,
    syncedAt: Long,
    syncError: String?,
  ) =
    withContext(Dispatchers.IO) {
      clipDao.updateSyncMetadata(
        id = clipId,
        syncStatus = syncStatus,
        syncError = syncError,
        syncedAt = if (syncStatus == ClipSyncState.SYNCED) syncedAt else null,
        telegramMsgId = if (providerId == SyncProviderId.TELEGRAM) remoteMessageOrFileId else null,
        discordMsgId = if (providerId == SyncProviderId.DISCORD) remoteMessageOrFileId else null,
        driveRevId = if (providerId == SyncProviderId.GOOGLE_DRIVE) remoteMessageOrFileId else null,
        remoteId = remoteMessageOrFileId,
      )
    }

  override suspend fun updateClipSyncStatus(clipId: Long, syncStatus: ClipSyncState, syncError: String?) =
    withContext(Dispatchers.IO) { clipDao.updateSyncStatus(clipId, syncStatus, syncError) }

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
    val parsedTags =
      if (tagsCsv.isBlank()) emptyList()
      else tagsCsv.split(",").mapNotNull { it.trim().takeIf(String::isNotEmpty) }
    val sizeBytes =
      if (contentSizeBytes > 0L) contentSizeBytes
      else plain.toByteArray(StandardCharsets.UTF_8).size.toLong()
    return ClipItem(
      id = id,
      title = title,
      content = plain,
      contentHash = contentHash,
      contentType = contentType,
      categoryId = categoryId,
      tags = parsedTags,
      isPinned = isPinned,
      isFavorite = isFavorite,
      isSensitive = isSensitive,
      isTrashed = isTrashed,
      trashedAt = trashedAt,
      createdAt = createdAt,
      updatedAt = updatedAt,
      source = source,
      remoteId = remoteId,
      syncStatus = syncStatus,
      contentSizeBytes = sizeBytes,
      telegramMessageId = telegramMessageId,
      discordMessageId = discordMessageId,
      driveRevisionId = driveRevisionId,
      lastSyncedAt = lastSyncedAt,
      syncError = syncError,
      parentClipId = parentClipId,
      versionNumber = versionNumber,
      simHash = simHash,
      useCount = useCount,
      lastPastedAt = lastPastedAt,
    )
  }
}

@Singleton
class SyncQueueRepositoryImpl @Inject constructor(private val queueDao: SyncQueueDao) :
  SyncQueueRepository {

  override fun observeQueueSummary(): Flow<QueueSummary> =
    kotlinx.coroutines.flow.combine(
      queueDao.observeQueueSummary(),
      queueDao.observeActiveQueueJobs(),
    ) { rows, activeJobs ->
      val map = rows.associate { it.status to it.itemCount }
      val perProvider = activeJobs.groupingBy { it.provider }.eachCount()
      QueueSummary(
        pendingCount = map[SyncQueueStatus.PENDING] ?: 0,
        uploadingCount = map[SyncQueueStatus.UPLOADING] ?: 0,
        failedCount = map[SyncQueueStatus.FAILED] ?: 0,
        syncedCount = map[SyncQueueStatus.SYNCED] ?: 0,
        conflictCount = map[SyncQueueStatus.CONFLICT] ?: 0,
        byProvider = perProvider,
      )
    }.flowOn(Dispatchers.Default)

  override fun observeQueueSummaryForProvider(providerId: SyncProviderId): Flow<QueueSummary> =
    queueDao
      .observeQueueSummaryForProvider(providerId)
      .map { rows ->
        val map = rows.associate { it.status to it.itemCount }
        val active =
          (map[SyncQueueStatus.PENDING] ?: 0) +
            (map[SyncQueueStatus.UPLOADING] ?: 0) +
            (map[SyncQueueStatus.FAILED] ?: 0)
        QueueSummary(
          pendingCount = map[SyncQueueStatus.PENDING] ?: 0,
          uploadingCount = map[SyncQueueStatus.UPLOADING] ?: 0,
          failedCount = map[SyncQueueStatus.FAILED] ?: 0,
          syncedCount = map[SyncQueueStatus.SYNCED] ?: 0,
          conflictCount = map[SyncQueueStatus.CONFLICT] ?: 0,
          byProvider = if (active > 0) mapOf(providerId to active) else emptyMap(),
        )
      }
      .flowOn(Dispatchers.Default)

  override fun observeAllJobs(): Flow<List<SyncQueueItem>> =
    queueDao
      .observeAllJobs()
      .map { rows -> rows.map { it.toDomain() } }
      .flowOn(Dispatchers.Default)

  override suspend fun enqueueClip(
    clipId: Long,
    providerId: SyncProviderId,
    operation: SyncQueueOperation,
    remoteTargetId: String?,
  ) {
    if (clipId <= 0L) return
    val now = System.currentTimeMillis()
    withContext(Dispatchers.IO) {
      queueDao.upsertJob(
        SyncQueueEntity(
          clipId = clipId,
          provider = providerId,
          operation = operation,
          remoteTargetId = remoteTargetId,
          status = SyncQueueStatus.PENDING,
          retryCount = 0,
          nextRetryAt = 0L,
          lastError = null,
          createdAt = now,
          updatedAt = now,
        )
      )
    }
  }

  override suspend fun getDueJobs(
    providerId: SyncProviderId,
    now: Long,
    limit: Int,
  ): List<SyncQueueItem> =
    withContext(Dispatchers.IO) {
      queueDao.getDueJobs(providerId, now, limit).map { it.toDomain() }
    }

  override suspend fun getAllDueJobs(now: Long, limit: Int): List<SyncQueueItem> =
    withContext(Dispatchers.IO) {
      queueDao.getAllDueJobs(now, limit).map { it.toDomain() }
    }

  override suspend fun requeueFailedJobs(providerId: SyncProviderId?) {
    withContext(Dispatchers.IO) {
      queueDao.requeueFailedJobs(providerId, System.currentTimeMillis())
    }
  }

  override suspend fun markUploading(jobId: Long) {
    withContext(Dispatchers.IO) { queueDao.markUploading(jobId, System.currentTimeMillis()) }
  }

  override suspend fun markSynced(jobId: Long) {
    withContext(Dispatchers.IO) { queueDao.markSynced(jobId, System.currentTimeMillis()) }
  }

  override suspend fun markFailed(
    jobId: Long,
    retryCount: Int,
    nextRetryAt: Long,
    error: String?,
    status: SyncQueueStatus,
  ) {
    withContext(Dispatchers.IO) {
      queueDao.markFailed(
        id = jobId,
        status = status,
        retryCount = retryCount,
        nextRetryAt = nextRetryAt,
        error = error,
        now = System.currentTimeMillis(),
      )
    }
  }

  override suspend fun deleteJobsForClips(clipIds: List<Long>) {
    if (clipIds.isEmpty()) return
    withContext(Dispatchers.IO) { queueDao.deleteForClips(clipIds) }
  }

  override suspend fun clearCompleted() {
    withContext(Dispatchers.IO) { queueDao.clearCompleted() }
  }

  override suspend fun clearAll() {
    withContext(Dispatchers.IO) { queueDao.clearAll() }
  }

  private fun SyncQueueEntity.toDomain() =
    SyncQueueItem(
      id = id,
      clipId = clipId,
      provider = provider,
      operation = operation,
      remoteTargetId = remoteTargetId,
      status = status,
      retryCount = retryCount,
      nextRetryAt = nextRetryAt,
      lastError = lastError,
      createdAt = createdAt,
      updatedAt = updatedAt,
    )
}

@Singleton
class CategoryRepositoryImpl @Inject constructor(private val categoryDao: CategoryDao) :
  CategoryRepository {

  override fun observeCategories(): Flow<List<Category>> =
    categoryDao
      .observeCategoriesWithCount()
      .distinctUntilChanged()
      .map { rows ->
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
      .flowOn(Dispatchers.Default)

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
    syncLogDao
      .observeRecentLogs()
      .map { rows ->
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
      .flowOn(Dispatchers.Default)

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
