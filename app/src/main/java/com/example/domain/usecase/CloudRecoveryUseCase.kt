package com.example.domain.usecase

import com.example.core.preferences.AppPreferencesRepository
import com.example.core.security.KeystoreCryptoManager
import com.example.domain.model.CloudRecoveryItem
import com.example.domain.model.ClipSyncState
import com.example.domain.model.ConflictResolution
import com.example.domain.model.ProviderHealthSummary
import com.example.domain.model.RecoveryApplySummary
import com.example.domain.model.RecoveryMatchStatus
import com.example.domain.model.RecoveryScanResult
import com.example.domain.model.SyncDirection
import com.example.domain.model.SyncLog
import com.example.domain.model.SyncProviderId
import com.example.domain.model.SyncStatus
import com.example.domain.repository.CategoryRepository
import com.example.domain.repository.ClipRepository
import com.example.domain.repository.SyncLogRepository
import com.example.domain.repository.SyncQueueRepository
import com.example.integrations.IntegrationProvider
import com.example.integrations.ProviderResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudRecoveryUseCase
@Inject
constructor(
  private val providers: Set<@JvmSuppressWildcards IntegrationProvider>,
  private val clipRepository: ClipRepository,
  private val categoryRepository: CategoryRepository,
  private val syncQueueRepository: SyncQueueRepository,
  private val syncLogRepository: SyncLogRepository,
  private val cryptoManager: KeystoreCryptoManager,
  private val prefs: AppPreferencesRepository,
) {

  suspend fun getProviderHealthSummaries(runLiveTest: Boolean = false): List<ProviderHealthSummary> {
    val tg = prefs.getTelegramConfigOnce()
    val dc = prefs.getDiscordConfigOnce()
    val gd = prefs.getGoogleDriveConfigOnce()
    val queueSummary = syncQueueRepository.observeQueueSummary().first()

    return SyncProviderId.entries.map { id ->
      val provider = providers.firstOrNull { it.providerId == id }
      val configured = runCatching { provider?.isConfigured() == true }.getOrDefault(false)
      val baseStatus =
        when (id) {
          SyncProviderId.TELEGRAM -> tg.lastStatus
          SyncProviderId.DISCORD -> dc.lastStatus
          SyncProviderId.GOOGLE_DRIVE -> gd.lastStatus
        }
      val lastSync =
        when (id) {
          SyncProviderId.TELEGRAM -> tg.lastSyncAt
          SyncProviderId.DISCORD -> dc.lastSyncAt
          SyncProviderId.GOOGLE_DRIVE -> gd.lastBackupAt
        }
      var healthy = configured
      var message = baseStatus.ifBlank { if (configured) "Configured" else "Not configured" }

      if (runLiveTest && configured && provider != null) {
        when (val res = provider.testConnection()) {
          is ProviderResult.Success -> {
            healthy = true
            message = res.data
          }
          is ProviderResult.Error -> {
            healthy = false
            message = res.message
          }
        }
      }

      val autoSync =
        when (id) {
          SyncProviderId.TELEGRAM -> tg.autoSync
          SyncProviderId.DISCORD -> dc.autoSync
          SyncProviderId.GOOGLE_DRIVE -> gd.autoIncrementalBackup
        }
      ProviderHealthSummary(
        providerId = id,
        isConnected = healthy && configured,
        isAutoSyncEnabled = autoSync,
        lastSyncAt = lastSync,
        lastBackupAt = if (id == SyncProviderId.GOOGLE_DRIVE) gd.lastBackupAt else null,
        pendingQueueCount = queueSummary.byProvider[id] ?: 0,
        failedQueueCount = if (!healthy && configured) 1 else 0,
        statusMessage = message,
      )
    }
  }

  suspend fun scanProviders(
    targetProviders: Set<SyncProviderId> = SyncProviderId.entries.toSet(),
    maxItemsPerProvider: Int = 200,
  ): RecoveryScanResult {
    val health = getProviderHealthSummaries(runLiveTest = false)
    val items = mutableListOf<CloudRecoveryItem>()
    val errors = mutableMapOf<SyncProviderId, String>()
    val seenKeys = HashSet<String>()

    for (provider in providers) {
      if (provider.providerId !in targetProviders) continue
      val configured = runCatching { provider.isConfigured() }.getOrDefault(false)
      if (!configured) continue

      try {
        when (val res = provider.fetchRecoveryItems(maxItemsPerProvider)) {
          is ProviderResult.Error -> {
            errors[provider.providerId] = res.message
          }
          is ProviderResult.Success -> {
            for (pulled in res.data) {
              val trimmed = pulled.content.trim()
              if (trimmed.isEmpty()) continue
              val hash = cryptoManager.sha256(trimmed)
              val localByHash = clipRepository.findActiveByHash(hash)
              val localByRemote = clipRepository.findByRemoteId(pulled.remoteId)
              val matchedLocal = localByHash ?: localByRemote

              val status =
                when {
                  matchedLocal == null -> RecoveryMatchStatus.NEW_REMOTE
                  localByHash != null -> RecoveryMatchStatus.ALREADY_SYNCED
                  matchedLocal.updatedAt > pulled.remoteTimestamp + 2_000L ->
                    RecoveryMatchStatus.LOCAL_NEWER
                  else -> RecoveryMatchStatus.CONFLICT
                }

              val uniqueKey = "${provider.providerId.id}:${pulled.remoteId}:$hash"
              if (!seenKeys.add(uniqueKey)) continue

              items.add(
                CloudRecoveryItem(
                  key = uniqueKey,
                  providerId = provider.providerId,
                  remoteId = pulled.remoteId,
                  title = pulled.title,
                  content = trimmed,
                  contentHash = hash,
                  categoryHint = pulled.categoryHint,
                  tags = pulled.tags,
                  remoteTimestamp = pulled.remoteTimestamp,
                  source = provider.providerId.toClipSource(),
                  localMatchId = matchedLocal?.id,
                  localTimestamp = matchedLocal?.updatedAt,
                  matchStatus = status,
                )
              )
            }
          }
        }
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: Exception) {
        errors[provider.providerId] = e.localizedMessage ?: "Scan failed"
      }
    }

    return RecoveryScanResult(
      providerId = if (targetProviders.size == 1) targetProviders.first() else null,
      items = items.sortedByDescending { it.remoteTimestamp },
      newRemoteCount = items.count { it.matchStatus == RecoveryMatchStatus.NEW_REMOTE },
      alreadySyncedCount = items.count { it.matchStatus == RecoveryMatchStatus.ALREADY_SYNCED },
      localNewerCount = items.count { it.matchStatus == RecoveryMatchStatus.LOCAL_NEWER },
      conflictCount = items.count { it.matchStatus == RecoveryMatchStatus.CONFLICT },
      healthSummaries = health,
      errorsByProvider = errors,
    )
  }

  suspend fun applyRecovery(
    items: List<CloudRecoveryItem>,
    resolutions: Map<String, ConflictResolution> = emptyMap(),
  ): RecoveryApplySummary {
    categoryRepository.ensureDefaultCategories()
    val categoriesByName =
      categoryRepository.getAllCategoriesOnce().associateBy { it.name.lowercase() }

    var restoredCount = 0
    var updatedFromCloudCount = 0
    var skippedDuplicatesCount = 0
    var skippedLocalNewerCount = 0
    var conflictsKeptBothCount = 0

    for (item in items) {
      when (item.matchStatus) {
        RecoveryMatchStatus.ALREADY_SYNCED -> {
          item.localMatchId?.let { localId ->
            clipRepository.updateProviderSyncMetadata(
              clipId = localId,
              providerId = item.providerId,
              remoteMessageOrFileId = item.remoteId,
              syncStatus = ClipSyncState.SYNCED,
              syncedAt = System.currentTimeMillis(),
              syncError = null,
            )
          }
          skippedDuplicatesCount++
        }
        RecoveryMatchStatus.LOCAL_NEWER -> {
          val resolution = resolutions[item.remoteId] ?: ConflictResolution.KEEP_LOCAL
          when (resolution) {
            ConflictResolution.KEEP_LOCAL -> skippedLocalNewerCount++
            ConflictResolution.KEEP_BOTH -> {
              val catId = item.categoryHint?.let { categoriesByName[it.lowercase()]?.id }
              val res =
                clipRepository.saveNewOrDeduplicate(
                  title = item.title,
                  content = item.content,
                  categoryId = catId,
                  tags = item.tags,
                  source = item.source,
                  remoteId = item.remoteId,
                  syncStatus = ClipSyncState.SYNCED,
                  updatedAtOverride = item.remoteTimestamp,
                )
              clipRepository.updateProviderSyncMetadata(
                clipId = res.clipId,
                providerId = item.providerId,
                remoteMessageOrFileId = item.remoteId,
                syncStatus = ClipSyncState.SYNCED,
                syncedAt = System.currentTimeMillis(),
                syncError = null,
              )
              restoredCount++
              conflictsKeptBothCount++
            }
            ConflictResolution.OVERWRITE_WITH_CLOUD -> {
              val local = item.localMatchId?.let { clipRepository.getClipById(it) }
              if (local != null) {
                clipRepository.updateClip(
                  local.copy(
                    title = item.title.ifBlank { local.title },
                    content = item.content,
                    contentHash = item.contentHash,
                    updatedAt = item.remoteTimestamp,
                    syncStatus = ClipSyncState.SYNCED,
                  )
                )
                clipRepository.updateProviderSyncMetadata(
                  clipId = local.id,
                  providerId = item.providerId,
                  remoteMessageOrFileId = item.remoteId,
                  syncStatus = ClipSyncState.SYNCED,
                  syncedAt = System.currentTimeMillis(),
                  syncError = null,
                )
                updatedFromCloudCount++
                restoredCount++
              }
            }
          }
        }
        RecoveryMatchStatus.CONFLICT -> {
          val resolution = resolutions[item.remoteId] ?: ConflictResolution.KEEP_BOTH
          when (resolution) {
            ConflictResolution.KEEP_LOCAL -> skippedLocalNewerCount++
            ConflictResolution.KEEP_BOTH -> {
              val catId = item.categoryHint?.let { categoriesByName[it.lowercase()]?.id }
              val res =
                clipRepository.saveNewOrDeduplicate(
                  title = item.title,
                  content = item.content,
                  categoryId = catId,
                  tags = item.tags,
                  source = item.source,
                  remoteId = item.remoteId,
                  syncStatus = ClipSyncState.SYNCED,
                  updatedAtOverride = item.remoteTimestamp,
                )
              clipRepository.updateProviderSyncMetadata(
                clipId = res.clipId,
                providerId = item.providerId,
                remoteMessageOrFileId = item.remoteId,
                syncStatus = ClipSyncState.SYNCED,
                syncedAt = System.currentTimeMillis(),
                syncError = null,
              )
              restoredCount++
              conflictsKeptBothCount++
            }
            ConflictResolution.OVERWRITE_WITH_CLOUD -> {
              val local = item.localMatchId?.let { clipRepository.getClipById(it) }
              if (local != null) {
                clipRepository.updateClip(
                  local.copy(
                    title = item.title.ifBlank { local.title },
                    content = item.content,
                    contentHash = item.contentHash,
                    updatedAt = item.remoteTimestamp,
                    syncStatus = ClipSyncState.SYNCED,
                  )
                )
                clipRepository.updateProviderSyncMetadata(
                  clipId = local.id,
                  providerId = item.providerId,
                  remoteMessageOrFileId = item.remoteId,
                  syncStatus = ClipSyncState.SYNCED,
                  syncedAt = System.currentTimeMillis(),
                  syncError = null,
                )
                updatedFromCloudCount++
                restoredCount++
              }
            }
          }
        }
        RecoveryMatchStatus.NEW_REMOTE -> {
          val catId = item.categoryHint?.let { categoriesByName[it.lowercase()]?.id }
          val saved =
            clipRepository.saveNewOrDeduplicate(
              title = item.title,
              content = item.content,
              categoryId = catId,
              tags = item.tags,
              source = item.source,
              remoteId = item.remoteId,
              syncStatus = ClipSyncState.SYNCED,
              updatedAtOverride = item.remoteTimestamp,
            )
          clipRepository.updateProviderSyncMetadata(
            clipId = saved.clipId,
            providerId = item.providerId,
            remoteMessageOrFileId = item.remoteId,
            syncStatus = ClipSyncState.SYNCED,
            syncedAt = System.currentTimeMillis(),
            syncError = null,
          )
          if (saved.wasDuplicateMerged) skippedDuplicatesCount++ else restoredCount++
        }
      }
    }

    if (restoredCount > 0) {
      syncLogRepository.addLog(
        SyncLog(
          provider = items.firstOrNull()?.providerId ?: SyncProviderId.TELEGRAM,
          direction = SyncDirection.PULL,
          status = SyncStatus.SUCCESS,
          message = "Recovery restored $restoredCount item(s), skipped $skippedDuplicatesCount existing",
          itemsCount = restoredCount,
        )
      )
    }

    return RecoveryApplySummary(
      providerId = items.firstOrNull()?.providerId,
      restoredCount = restoredCount,
      updatedFromCloudCount = updatedFromCloudCount,
      skippedDuplicatesCount = skippedDuplicatesCount,
      skippedLocalNewerCount = skippedLocalNewerCount,
      conflictsKeptBothCount = conflictsKeptBothCount,
    )
  }

  private fun SyncProviderId.toClipSource() =
    when (this) {
      SyncProviderId.TELEGRAM -> com.example.domain.model.ClipSource.TELEGRAM
      SyncProviderId.DISCORD -> com.example.domain.model.ClipSource.DISCORD
      SyncProviderId.GOOGLE_DRIVE -> com.example.domain.model.ClipSource.GOOGLE_DRIVE
    }
}
