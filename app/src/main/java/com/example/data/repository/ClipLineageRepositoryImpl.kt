package com.example.data.repository

import androidx.room.withTransaction
import com.example.core.clipboard.SystemClipboardHelper
import com.example.core.security.KeystoreCryptoManager
import com.example.data.local.ClipDao
import com.example.data.local.ClipEntity
import com.example.data.local.ClipRevisionDao
import com.example.data.local.ClipRevisionEntity
import com.example.data.local.SmartClipboardDatabase
import com.example.domain.lineage.ClipLineageEngine
import com.example.domain.lineage.LineDiffEngine
import com.example.domain.lineage.SimHash64
import com.example.domain.model.ClipContentType
import com.example.domain.model.ClipItem
import com.example.domain.model.ClipRevision
import com.example.domain.model.ClipSource
import com.example.domain.model.ClipSyncState
import com.example.domain.repository.ClipLineageRepository
import com.example.domain.repository.SaveClipResult
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
class ClipLineageRepositoryImpl
@Inject
constructor(
  private val database: SmartClipboardDatabase,
  private val clipDao: ClipDao,
  private val clipRevisionDao: ClipRevisionDao,
  private val cryptoManager: KeystoreCryptoManager,
  private val clipboardHelper: SystemClipboardHelper,
  private val lineageEngine: ClipLineageEngine,
) : ClipLineageRepository {

  override fun observeRevisions(clipId: Long): Flow<List<ClipRevision>> =
    clipRevisionDao
      .observeRevisions(clipId)
      .distinctUntilChanged()
      .map { rows -> rows.map { it.toDomain() } }
      .flowOn(Dispatchers.Default)

  override suspend fun getRevisionsOnce(clipId: Long): List<ClipRevision> =
    withContext(Dispatchers.IO) { clipRevisionDao.getRevisionsOnce(clipId).map { it.toDomain() } }

  override suspend fun tryMergeIntoLineage(
    newContent: String,
    newContentHash: String,
    contentType: ClipContentType,
    tags: List<String>,
    isSensitive: Boolean,
    source: ClipSource,
    nowMs: Long,
  ): SaveClipResult? =
    withContext(Dispatchers.IO) {
      val trimmed = newContent.trim()
      if (!lineageEngine.isEligibleForLineage(trimmed, contentType, isSensitive)) {
        return@withContext null
      }

      val cutoff = nowMs - ClipLineageEngine.LINEAGE_WINDOW_MS
      val candidateEntities =
        clipDao.findLineageCandidates(
          contentType = contentType,
          minUpdatedAt = cutoff,
          limit = 20,
        )
      if (candidateEntities.isEmpty()) return@withContext null

      val domainCandidates = candidateEntities.map { it.toPlainDomain() }
      val match =
        lineageEngine.findBestMatch(
          newContent = trimmed,
          newHash = newContentHash,
          newType = contentType,
          isSensitive = isSensitive,
          candidates = domainCandidates,
          nowMs = nowMs,
        ) ?: return@withContext null

      val targetEntity =
        candidateEntities.firstOrNull { it.id == match.targetClip.id } ?: return@withContext null

      val newVersion = targetEntity.versionNumber + 1
      database.withTransaction {
        clipRevisionDao.insertRevision(
          ClipRevisionEntity(
            clipId = targetEntity.id,
            versionNumber = targetEntity.versionNumber,
            content = targetEntity.content,
            contentHash = targetEntity.contentHash,
            diffSummary = match.diffSummary,
            createdAt = targetEntity.updatedAt,
          )
        )
        clipRevisionDao.trimOldRevisions(
          clipId = targetEntity.id,
          maxToKeep = ClipLineageEngine.MAX_REVISIONS_PER_CLIP,
        )

        val mergedTags =
          (targetEntity.tagsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() } + tags)
            .distinct()
            .joinToString(",")
        val nextSyncState =
          if (targetEntity.syncStatus == ClipSyncState.SYNCED) ClipSyncState.PENDING
          else targetEntity.syncStatus
        val updatedEntity =
          targetEntity.copy(
            content = trimmed,
            contentHash = newContentHash,
            tagsCsv = mergedTags,
            updatedAt = maxOf(targetEntity.updatedAt, nowMs),
            source = source,
            syncStatus = nextSyncState,
            contentSizeBytes = trimmed.toByteArray(StandardCharsets.UTF_8).size.toLong(),
            versionNumber = newVersion,
            simHash = match.newSimHash,
          )
        clipDao.updateClip(updatedEntity)
      }

      SaveClipResult(
        clipId = targetEntity.id,
        wasDuplicateMerged = false,
        wasLineageVersioned = true,
        newVersionNumber = newVersion,
      )
    }

  override suspend fun restoreRevision(clipId: Long, revisionId: Long): Boolean =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val clip = clipDao.getClipById(clipId) ?: return@withTransaction false
        if (clip.isSensitive) return@withTransaction false
        val rev = clipRevisionDao.getRevisionById(revisionId) ?: return@withTransaction false
        if (rev.clipId != clipId) return@withTransaction false

        val now = System.currentTimeMillis()
        val diffBadge = LineDiffEngine.diff(clip.content, rev.content).summaryBadge
        clipRevisionDao.insertRevision(
          ClipRevisionEntity(
            clipId = clip.id,
            versionNumber = clip.versionNumber,
            content = clip.content,
            contentHash = clip.contentHash,
            diffSummary = diffBadge,
            createdAt = clip.updatedAt,
          )
        )
        clipRevisionDao.deleteRevisionById(rev.id)
        clipRevisionDao.trimOldRevisions(
          clipId = clip.id,
          maxToKeep = ClipLineageEngine.MAX_REVISIONS_PER_CLIP,
        )

        val restoredSimHash = SimHash64.compute(rev.content)
        val restoredBytes = rev.content.toByteArray(StandardCharsets.UTF_8).size.toLong()
        clipDao.updateClip(
          clip.copy(
            content = rev.content,
            contentHash = rev.contentHash,
            contentSizeBytes = restoredBytes,
            updatedAt = now,
            versionNumber = clip.versionNumber + 1,
            simHash = restoredSimHash,
            syncStatus =
              if (clip.syncStatus == ClipSyncState.SYNCED) ClipSyncState.PENDING
              else clip.syncStatus,
          )
        )
        true
      }
    }

  override suspend fun splitRevisionAsStandalone(clipId: Long, revisionId: Long): Long? =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        val parent = clipDao.getClipById(clipId) ?: return@withTransaction null
        if (parent.isSensitive) return@withTransaction null
        val rev = clipRevisionDao.getRevisionById(revisionId) ?: return@withTransaction null
        if (rev.clipId != clipId) return@withTransaction null

        val existingExact = clipDao.findActiveByHash(rev.contentHash)
        if (existingExact != null && existingExact.id != clipId) {
          clipRevisionDao.deleteRevisionById(rev.id)
          return@withTransaction existingExact.id
        }

        val now = System.currentTimeMillis()
        val resolvedType = clipboardHelper.classifyContent(rev.content)
        val newId =
          clipDao.insertClip(
            ClipEntity(
              title =
                if (parent.title.isNotBlank()) "${parent.title} (v${rev.versionNumber})" else "",
              content = rev.content,
              contentHash = rev.contentHash,
              contentType = resolvedType,
              categoryId = parent.categoryId,
              tagsCsv = parent.tagsCsv,
              isPinned = false,
              isFavorite = false,
              isSensitive = false,
              isTrashed = false,
              createdAt = rev.createdAt,
              updatedAt = now,
              source = parent.source,
              syncStatus = ClipSyncState.LOCAL_ONLY,
              contentSizeBytes = rev.content.toByteArray(StandardCharsets.UTF_8).size.toLong(),
              parentClipId = parent.id,
              versionNumber = 1,
              simHash = SimHash64.compute(rev.content),
            )
          )
        clipRevisionDao.deleteRevisionById(rev.id)
        newId
      }
    }

  override suspend fun clearAllRevisions() =
    withContext(Dispatchers.IO) {
      database.withTransaction {
        clipRevisionDao.deleteAllRevisions()
        clipDao.clearAllLineageMetadata()
      }
    }

  private fun ClipRevisionEntity.toDomain() =
    ClipRevision(
      id = id,
      clipId = clipId,
      versionNumber = versionNumber,
      content = content,
      contentHash = contentHash,
      diffSummary = diffSummary,
      createdAt = createdAt,
    )

  private fun ClipEntity.toPlainDomain(): ClipItem {
    val tags =
      if (tagsCsv.isBlank()) emptyList()
      else tagsCsv.split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty) }
    return ClipItem(
      id = id,
      title = title,
      content = content,
      contentHash = contentHash,
      contentType = contentType,
      categoryId = categoryId,
      tags = tags,
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
      contentSizeBytes = contentSizeBytes,
      parentClipId = parentClipId,
      versionNumber = versionNumber,
      simHash = simHash,
      useCount = useCount,
      lastPastedAt = lastPastedAt,
    )
  }
}
