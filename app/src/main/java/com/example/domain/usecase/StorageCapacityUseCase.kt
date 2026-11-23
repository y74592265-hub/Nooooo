package com.example.domain.usecase

import android.content.Context
import android.os.StatFs
import com.example.core.preferences.AppPreferencesRepository
import com.example.domain.model.ClipItem
import com.example.domain.model.ClipSyncState
import com.example.domain.model.StorageCapacityStats
import com.example.domain.model.StorageThresholdLevel
import com.example.domain.repository.ClipRepository
import com.example.domain.repository.SyncQueueRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageCapacityUseCase
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val clipRepository: ClipRepository,
  private val syncQueueRepository: SyncQueueRepository,
  private val prefs: AppPreferencesRepository,
) {

  suspend fun computeStats(largestLimit: Int = 15): StorageCapacityStats =
    withContext(Dispatchers.IO) {
      val settings = prefs.userSettings.first()
      val snapshot = clipRepository.getStorageAggregateSnapshot()
      val queue = syncQueueRepository.observeQueueSummary().first()
      val largest = clipRepository.getLargestClips(largestLimit)

      val dbFile = context.getDatabasePath("smart_clipboard.db")
      val walFile = context.getDatabasePath("smart_clipboard.db-wal")
      val shmFile = context.getDatabasePath("smart_clipboard.db-shm")
      val dbBytes =
        (if (dbFile.exists()) dbFile.length() else 0L) +
          (if (walFile.exists()) walFile.length() else 0L) +
          (if (shmFile.exists()) shmFile.length() else 0L)

      val (deviceAvailableBytes, deviceTotalBytes) = readDeviceStorageStatFs()

      val activeBytes = (snapshot.totalContentBytes - snapshot.trashedBytes).coerceAtLeast(0L)
      val quotaBytes = settings.storageSoftQuotaMb.coerceAtLeast(1) * 1024L * 1024L
      val trackedUsedBytes = maxOf(dbBytes, snapshot.totalContentBytes)
      val usagePercent =
        ((trackedUsedBytes.toFloat() / quotaBytes.toFloat()) * 100f).toInt().coerceIn(0, 999)

      val level =
        when {
          usagePercent >= settings.storageWarnThreshold3 -> StorageThresholdLevel.CRITICAL_95
          usagePercent >= settings.storageWarnThreshold2 -> StorageThresholdLevel.HIGH_85
          usagePercent >= settings.storageWarnThreshold1 -> StorageThresholdLevel.WARNING_70
          else -> StorageThresholdLevel.NORMAL
        }

      StorageCapacityStats(
        totalClipsCount = snapshot.totalCount,
        activeClipsCount = snapshot.activeCount,
        trashedClipsCount = snapshot.trashedCount,
        sensitiveClipsCount = snapshot.sensitiveCount,
        pendingSyncCount = snapshot.pendingSyncCount + queue.pendingCount,
        failedSyncCount = snapshot.failedSyncCount + queue.failedCount,
        totalContentBytes = snapshot.totalContentBytes,
        activeContentBytes = activeBytes,
        trashedBytes = snapshot.trashedBytes,
        databaseFileBytes = dbBytes,
        deviceAvailableBytes = deviceAvailableBytes,
        deviceTotalBytes = deviceTotalBytes,
        softQuotaMb = settings.storageSoftQuotaMb,
        softQuotaBytes = quotaBytes,
        usagePercent = usagePercent,
        thresholdLevel = level,
        largestClips = largest,
      )
    }

  fun isSafeToDeleteWithoutCloudLoss(clip: ClipItem): Boolean =
    clip.syncStatus == ClipSyncState.SYNCED &&
      (!clip.telegramMessageId.isNullOrBlank() ||
        !clip.discordMessageId.isNullOrBlank() ||
        !clip.driveRevisionId.isNullOrBlank() ||
        !clip.remoteId.isNullOrBlank())

  private fun readDeviceStorageStatFs(): Pair<Long, Long> =
    runCatching {
      val stat = StatFs(context.filesDir.absolutePath)
      stat.availableBytes to stat.totalBytes
    }.getOrDefault(0L to 0L)
}
