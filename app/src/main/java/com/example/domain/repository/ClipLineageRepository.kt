package com.example.domain.repository

import com.example.domain.model.ClipContentType
import com.example.domain.model.ClipRevision
import com.example.domain.model.ClipSource
import kotlinx.coroutines.flow.Flow

interface ClipLineageRepository {
  fun observeRevisions(clipId: Long): Flow<List<ClipRevision>>

  suspend fun getRevisionsOnce(clipId: Long): List<ClipRevision>

  suspend fun tryMergeIntoLineage(
    newContent: String,
    newContentHash: String,
    contentType: ClipContentType,
    tags: List<String>,
    isSensitive: Boolean,
    source: ClipSource,
    nowMs: Long = System.currentTimeMillis(),
  ): SaveClipResult?

  suspend fun restoreRevision(clipId: Long, revisionId: Long): Boolean

  suspend fun splitRevisionAsStandalone(clipId: Long, revisionId: Long): Long?

  suspend fun clearAllRevisions()
}
