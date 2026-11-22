package com.example.domain.model

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
  SHARE_SHEET,
  TELEGRAM,
  DISCORD,
  RESTORE,
}

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
)

object CategoryDefaults {
  const val PERSONAL_ID = 1L
  const val WORK_ID = 2L
  const val PROGRAMMING_ID = 3L
  const val LINKS_ID = 4L
  const val NOTES_ID = 5L
  const val IMPORTANT_ID = 6L
}
