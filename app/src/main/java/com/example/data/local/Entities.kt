package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.domain.model.ClipContentType
import com.example.domain.model.ClipSource
import com.example.domain.model.SyncDirection
import com.example.domain.model.SyncProviderId
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
