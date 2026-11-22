package com.example.domain.model

data class Category(
  val id: Long = 0L,
  val name: String,
  val colorHex: Long,
  val iconKey: String = "folder",
  val isDefault: Boolean = false,
  val sortOrder: Int = 0,
  val clipCount: Int = 0,
)

enum class SyncProviderId(val id: String, val displayName: String) {
  TELEGRAM("telegram", "Telegram"),
  DISCORD("discord", "Discord"),
  GOOGLE_DRIVE("google_drive", "Google Drive"),
}

enum class SyncStatus {
  IDLE,
  SYNCING,
  SUCCESS,
  PARTIAL_ERROR,
  ERROR,
}

enum class SyncDirection {
  PUSH,
  PULL,
  BACKUP,
  RESTORE,
}

data class SyncLog(
  val id: Long = 0L,
  val provider: SyncProviderId,
  val direction: SyncDirection,
  val status: SyncStatus,
  val message: String,
  val itemsCount: Int = 0,
  val timestamp: Long = System.currentTimeMillis(),
)
