package com.example.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class BackupClipDto(
  val title: String = "",
  val content: String,
  val contentHash: String,
  val contentType: ClipContentType,
  val categoryName: String,
  val categoryColorHex: Long,
  val tags: List<String> = emptyList(),
  val isPinned: Boolean = false,
  val isFavorite: Boolean = false,
  val isSensitive: Boolean = false,
  val createdAt: Long,
  val updatedAt: Long,
  val source: ClipSource = ClipSource.RESTORE,
  val remoteId: String? = null,
)

@Serializable
data class BackupCategoryDto(
  val name: String,
  val colorHex: Long,
  val iconKey: String = "folder",
  val isDefault: Boolean = false,
)

@Serializable
data class BackupPayload(
  val schemaVersion: Int = 2,
  val isIncremental: Boolean = false,
  val baseBackupFileId: String? = null,
  val exportedAt: Long = System.currentTimeMillis(),
  val appVersion: String = "1.0",
  val categories: List<BackupCategoryDto> = emptyList(),
  val clips: List<BackupClipDto> = emptyList(),
  val sha256Checksum: String = "",
)

@Serializable
data class EncryptedBackupEnvelope(
  val format: String = "SMART_CLIPBOARD_ENC_V1",
  val saltBase64: String,
  val ivBase64: String,
  val cipherTextBase64: String,
  val iterations: Int = 100_000,
)

@Serializable
data class TelegramConfig(
  val enabled: Boolean = false,
  val botToken: String = "",
  val chatId: String = "",
  val autoSync: Boolean = false,
  val lastUpdateOffset: Long = 0L,
  val lastSyncAt: Long? = null,
  val lastStatus: String = "",
)

@Serializable
data class DiscordConfig(
  val enabled: Boolean = false,
  val webhookUrl: String = "",
  val botToken: String = "",
  val channelId: String = "",
  val channelName: String = "",
  val selectedGuildId: String = "",
  val selectedGuildName: String = "",
  val allowedUserId: String = "",
  val allowedRoleId: String = "",
  val autoSync: Boolean = false,
  val lastMessageId: String? = null,
  val lastSyncAt: Long? = null,
  val lastStatus: String = "",
)

@Serializable
data class GoogleDriveConfig(
  val enabled: Boolean = false,
  val accountEmail: String = "",
  val useAppDataFolder: Boolean = false,
  val selectedFolderId: String? = null,
  val selectedFolderName: String = "Smart Clipboard Backup",
  val encryptBackups: Boolean = true,
  val autoBackup: Boolean = false,
  val autoIncrementalBackup: Boolean = true,
  val lastBackupAt: Long? = null,
  val lastBackupFileId: String? = null,
  val lastBackupHashCount: Int = 0,
  val lastStatus: String = "",
)
