package com.example.data.local

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.domain.model.ClipContentType
import com.example.domain.model.ClipSource
import com.example.domain.model.ClipSyncState
import com.example.domain.model.SyncDirection
import com.example.domain.model.SyncProviderId
import com.example.domain.model.SyncQueueOperation
import com.example.domain.model.SyncQueueStatus
import com.example.domain.model.SyncStatus

@Entity(
  tableName = "clips",
  indices =
    [
      Index(value = ["contentHash"]),
      Index(value = ["categoryId"]),
      Index(value = ["isTrashed", "isPinned", "updatedAt"]),
      Index(value = ["isFavorite"]),
      Index(value = ["contentType"]),
      Index(value = ["syncStatus"]),
    ],
)
data class ClipEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val title: String = "",
  val content: String,
  val contentHash: String,
  val contentType: ClipContentType = ClipContentType.TEXT,
  val categoryId: Long = 1L,
  val tagsCsv: String = "",
  val isPinned: Boolean = false,
  val isFavorite: Boolean = false,
  val isSensitive: Boolean = false,
  val isTrashed: Boolean = false,
  val trashedAt: Long? = null,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
  val source: ClipSource = ClipSource.MANUAL,
  val remoteId: String? = null,
  val syncStatus: ClipSyncState = ClipSyncState.LOCAL_ONLY,
  val contentSizeBytes: Long = 0L,
  val telegramMessageId: String? = null,
  val discordMessageId: String? = null,
  val driveRevisionId: String? = null,
  val lastSyncedAt: Long? = null,
  val syncError: String? = null,
)

@Fts4(contentEntity = ClipEntity::class)
@Entity(tableName = "clips_fts")
data class ClipFtsEntity(
  val title: String,
  val content: String,
  val tagsCsv: String,
)

@Entity(
  tableName = "sync_queue",
  indices =
    [
      Index(value = ["provider", "status", "nextRetryAt"]),
      Index(value = ["clipId", "provider", "operation"], unique = true),
    ],
)
data class SyncQueueEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val clipId: Long,
  val provider: SyncProviderId,
  val operation: SyncQueueOperation = SyncQueueOperation.PUSH,
  val remoteTargetId: String? = null,
  val status: SyncQueueStatus = SyncQueueStatus.PENDING,
  val retryCount: Int = 0,
  val nextRetryAt: Long = 0L,
  val lastError: String? = null,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "categories")
data class CategoryEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val name: String,
  val colorHex: Long,
  val iconKey: String = "folder",
  val isDefault: Boolean = false,
  val sortOrder: Int = 0,
)

@Entity(tableName = "sync_logs", indices = [Index(value = ["timestamp"])])
data class SyncLogEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val provider: SyncProviderId,
  val direction: SyncDirection,
  val status: SyncStatus,
  val message: String,
  val itemsCount: Int = 0,
  val timestamp: Long = System.currentTimeMillis(),
)
