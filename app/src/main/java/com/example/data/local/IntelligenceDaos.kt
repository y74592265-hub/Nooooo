package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.domain.model.ClipContentType
import kotlinx.coroutines.flow.Flow

data class ImeClipProjectionRow(
  val id: Long,
  val title: String,
  val preview: String,
  val contentType: ClipContentType,
  val categoryId: Long,
  val isPinned: Boolean,
  val isFavorite: Boolean,
  val useCount: Int,
  val lastPastedAt: Long?,
  val updatedAt: Long,
  val versionNumber: Int,
)

@Dao
interface ClipRevisionDao {
  @Query("SELECT * FROM clip_revisions WHERE clipId = :clipId ORDER BY versionNumber DESC")
  fun observeRevisions(clipId: Long): Flow<List<ClipRevisionEntity>>

  @Query("SELECT * FROM clip_revisions WHERE clipId = :clipId ORDER BY versionNumber DESC")
  suspend fun getRevisionsOnce(clipId: Long): List<ClipRevisionEntity>

  @Query("SELECT * FROM clip_revisions WHERE id = :revisionId LIMIT 1")
  suspend fun getRevisionById(revisionId: Long): ClipRevisionEntity?

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertRevision(revision: ClipRevisionEntity): Long

  @Query(
    """
    DELETE FROM clip_revisions
    WHERE clipId = :clipId
      AND id NOT IN (
        SELECT id FROM clip_revisions
        WHERE clipId = :clipId
        ORDER BY versionNumber DESC
        LIMIT :maxToKeep
      )
    """
  )
  suspend fun trimOldRevisions(clipId: Long, maxToKeep: Int = 15)

  @Query("DELETE FROM clip_revisions WHERE id = :revisionId")
  suspend fun deleteRevisionById(revisionId: Long)

  @Query("DELETE FROM clip_revisions")
  suspend fun deleteAllRevisions()
}

@Dao
interface ClipTransitionDao {
  @Query(
    """
    SELECT * FROM clip_transitions
    WHERE fromClipId = :fromClipId
    ORDER BY weight DESC, lastTriggeredAt DESC
    LIMIT :limit
    """
  )
  suspend fun getTopTransitionsFrom(fromClipId: Long, limit: Int = 6): List<ClipTransitionEntity>

  @Query("SELECT * FROM clip_transitions WHERE fromClipId = :fromClipId AND toClipId = :toClipId LIMIT 1")
  suspend fun getEdge(fromClipId: Long, toClipId: Long): ClipTransitionEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertEdge(edge: ClipTransitionEntity)

  @Query(
    """
    DELETE FROM clip_transitions
    WHERE fromClipId = :fromClipId
      AND toClipId NOT IN (
        SELECT toClipId FROM clip_transitions
        WHERE fromClipId = :fromClipId
        ORDER BY weight DESC, lastTriggeredAt DESC
        LIMIT :maxEdgesPerSource
      )
    """
  )
  suspend fun trimEdgesForSource(fromClipId: Long, maxEdgesPerSource: Int = 15)

  @Query("DELETE FROM clip_transitions WHERE lastTriggeredAt < :cutoffTimestamp OR weight < :minWeight")
  suspend fun pruneStaleTransitions(cutoffTimestamp: Long, minWeight: Float = 0.15f)

  @Query("DELETE FROM clip_transitions")
  suspend fun clearAllTransitions()

  @Transaction
  suspend fun recordTransition(
    fromClipId: Long,
    toClipId: Long,
    nowMs: Long,
    halfLifeMs: Long = 14L * 24L * 60L * 60L * 1000L,
  ) {
    val existing = getEdge(fromClipId, toClipId)
    val nextWeight: Float
    val nextHits: Int
    if (existing == null) {
      nextWeight = 1.0f
      nextHits = 1
    } else {
      val elapsed = (nowMs - existing.lastTriggeredAt).coerceAtLeast(0L).toDouble()
      val decay = Math.pow(0.5, elapsed / halfLifeMs.toDouble()).toFloat()
      nextWeight = (existing.weight * decay) + 1.0f
      nextHits = existing.hitCount + 1
    }
    upsertEdge(
      ClipTransitionEntity(
        fromClipId = fromClipId,
        toClipId = toClipId,
        weight = nextWeight.coerceAtMost(50f),
        hitCount = nextHits,
        lastTriggeredAt = nowMs,
      )
    )
    trimEdgesForSource(fromClipId)
  }
}
