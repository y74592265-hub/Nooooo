package com.example.domain.model

import androidx.compose.runtime.Immutable

enum class RecoveryMatchStatus {
  NEW_REMOTE,
  ALREADY_SYNCED,
  LOCAL_NEWER,
  CONFLICT,
}

enum class ConflictResolution {
  KEEP_LOCAL,
  OVERWRITE_WITH_CLOUD,
  KEEP_BOTH,
}

data class CloudRecoveryItem(
  val key: String,
  val providerId: SyncProviderId,
  val remoteId: String,
  val title: String = "",
  val content: String,
  val contentHash: String,
  val categoryHint: String? = null,
  val tags: List<String> = emptyList(),
  val remoteTimestamp: Long = System.currentTimeMillis(),
  val source: ClipSource,
  val localMatchId: Long? = null,
  val localTimestamp: Long? = null,
  val matchStatus: RecoveryMatchStatus = RecoveryMatchStatus.NEW_REMOTE,
  val backupFileId: String? = null,
  val backupFileName: String? = null,
)

data class ProviderHealthSummary(
  val providerId: SyncProviderId,
  val isConnected: Boolean = false,
  val isAutoSyncEnabled: Boolean = false,
  val lastSyncAt: Long? = null,
  val lastBackupAt: Long? = null,
  val remoteItemsCount: Int = 0,
  val pendingQueueCount: Int = 0,
  val failedQueueCount: Int = 0,
  val statusMessage: String = "",
)

data class RecoveryScanResult(
  val providerId: SyncProviderId? = null,
  val items: List<CloudRecoveryItem>,
  val newRemoteCount: Int,
  val alreadySyncedCount: Int,
  val localNewerCount: Int,
  val conflictCount: Int,
  val backupVersionName: String? = null,
  val healthSummaries: List<ProviderHealthSummary> = emptyList(),
  val errorsByProvider: Map<SyncProviderId, String> = emptyMap(),
)

data class RecoveryApplySummary(
  val providerId: SyncProviderId? = null,
  val restoredCount: Int,
  val updatedFromCloudCount: Int,
  val skippedDuplicatesCount: Int,
  val skippedLocalNewerCount: Int,
  val conflictsKeptBothCount: Int,
)

enum class StorageThresholdLevel {
  NORMAL,
  WARNING_70,
  HIGH_85,
  CRITICAL_95,
}

@Immutable
data class StorageCapacityStats(
  val totalClipsCount: Int = 0,
  val activeClipsCount: Int = 0,
  val trashedClipsCount: Int = 0,
  val sensitiveClipsCount: Int = 0,
  val pendingSyncCount: Int = 0,
  val failedSyncCount: Int = 0,
  val totalContentBytes: Long = 0L,
  val activeContentBytes: Long = 0L,
  val trashedBytes: Long = 0L,
  val databaseFileBytes: Long = 0L,
  val deviceAvailableBytes: Long = 0L,
  val deviceTotalBytes: Long = 0L,
  val softQuotaMb: Int = 50,
  val softQuotaBytes: Long = 50L * 1024L * 1024L,
  val usagePercent: Int = 0,
  val thresholdLevel: StorageThresholdLevel = StorageThresholdLevel.NORMAL,
  val largestClips: List<ClipItem> = emptyList(),
)

@Immutable
data class QueueSummary(
  val pendingCount: Int = 0,
  val uploadingCount: Int = 0,
  val failedCount: Int = 0,
  val syncedCount: Int = 0,
  val conflictCount: Int = 0,
  val byProvider: Map<SyncProviderId, Int> = emptyMap(),
)
