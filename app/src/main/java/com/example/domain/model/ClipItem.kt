package com.example.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
enum class ClipContentType {
  TEXT,
  LINK,
  CODE,
  NOTE,
  ADDRESS,
}

@Serializable
enum class ClipSource {
  MANUAL,
  CLIPBOARD,
  IME,
  SHARE_SHEET,
  TELEGRAM,
  DISCORD,
  GOOGLE_DRIVE,
  RESTORE,
}

@Serializable
enum class ClipSyncState {
  LOCAL_ONLY,
  PENDING,
  UPLOADING,
  SYNCED,
  FAILED,
  CONFLICT,
}

@Immutable
data class ClipItem(
  val id: Long = 0L,
  val title: String = "",
  val content: String,
  val contentHash: String,
  val contentType: ClipContentType = ClipContentType.TEXT,
  val categoryId: Long = CategoryDefaults.PERSONAL_ID,
  val tags: List<String> = emptyList(),
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
  val contentSizeBytes: Long = content.toByteArray().size.toLong(),
  val telegramMessageId: String? = null,
  val discordMessageId: String? = null,
  val driveRevisionId: String? = null,
  val lastSyncedAt: Long? = null,
  val syncError: String? = null,
  val parentClipId: Long? = null,
  val versionNumber: Int = 1,
  val simHash: Long = 0L,
  val useCount: Int = 0,
  val lastPastedAt: Long? = null,
)

object CategoryDefaults {
  const val PERSONAL_ID = 1L
  const val WORK_ID = 2L
  const val PROGRAMMING_ID = 3L
  const val LINKS_ID = 4L
  const val NOTES_ID = 5L
  const val IMPORTANT_ID = 6L
}
