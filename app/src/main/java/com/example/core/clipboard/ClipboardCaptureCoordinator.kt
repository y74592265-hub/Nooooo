package com.example.core.clipboard

import com.example.core.preferences.AppPreferencesRepository
import com.example.core.security.KeystoreCryptoManager
import com.example.core.sync.SyncEngine
import com.example.domain.model.ClipItem
import com.example.domain.model.ClipSource
import com.example.domain.model.ClipSyncState
import com.example.domain.model.SyncProviderId
import com.example.domain.model.SyncQueueOperation
import com.example.domain.repository.ClipRepository
import com.example.domain.repository.SyncQueueRepository
import com.example.domain.usecase.SaveClipUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

sealed interface CaptureOutcome {
  data object EmptyOrDisabled : CaptureOutcome

  data object AlreadyCaptured : CaptureOutcome

  data object ExcludedByRule : CaptureOutcome

  data object SkippedSensitive : CaptureOutcome

  data class Saved(
    val clip: ClipItem,
    val wasDuplicateMerged: Boolean,
    val autoMarkedSensitive: Boolean,
    val sensitiveReason: String?,
    val queuedProviders: List<SyncProviderId>,
  ) : CaptureOutcome
}

/**
 * Orchestrates zero-prompt automatic clipboard capture from Foreground Focus, Share Sheet,
 * or Optional Companion IME:
 * - Enforces deduplication via SHA-256 against lastDetectedClipHash.
 * - Applies app/keyword exclusion rules.
 * - Evaluates SensitiveContentDetector (API 33+ EXTRA_IS_SENSITIVE, OTP, Passwords, Tokens, Cards).
 * - Encrypts sensitive items locally and blocks automatic cloud upload when blockSensitiveCloudSync=true.
 * - Enqueues persistent SyncQueue jobs for each enabled auto-sync provider and schedules WorkManager.
 */
@Singleton
class ClipboardCaptureCoordinator
@Inject
constructor(
  private val clipboardHelper: SystemClipboardHelper,
  private val clipRepository: ClipRepository,
  private val saveClipUseCase: SaveClipUseCase,
  private val syncQueueRepository: SyncQueueRepository,
  private val cryptoManager: KeystoreCryptoManager,
  private val prefs: AppPreferencesRepository,
  private val syncEngine: SyncEngine,
  private val sensitiveDetector: SensitiveContentDetector = SensitiveContentDetector(),
) {
  private val captureMutex = Mutex()

  suspend fun captureFromSystemClipboard(
    source: ClipSource = ClipSource.CLIPBOARD,
    foregroundPackageHint: String? = null,
    overrideSnapshot: SystemClipboardSnapshot? = null,
    allowWhenAutoCaptureDisabled: Boolean = false,
  ): CaptureOutcome =
    captureMutex.withLock {
      val settings = prefs.userSettings.first()
      if (!allowWhenAutoCaptureDisabled && !settings.autoCaptureOnForeground && !settings.instantAutoSave) {
        return@withLock CaptureOutcome.EmptyOrDisabled
      }

      val lastHash = prefs.getLastDetectedClipHashOnce()
      val snapshot = overrideSnapshot ?: clipboardHelper.readCurrentClipboardSnapshot()
        ?: return@withLock CaptureOutcome.EmptyOrDisabled
      captureSnapshotLocked(
        snapshot = snapshot,
        source = source,
        foregroundPackageHint = foregroundPackageHint,
        lastDetectedHash = lastHash,
        excludedSourceAppsCsv = settings.excludedSourceAppsCsv,
        autoDetectSensitivePatterns = settings.autoDetectSensitivePatterns,
        skipAutoSaveSensitive = settings.skipAutoSaveSensitive,
        blockSensitiveCloudSync = settings.blockSensitiveCloudSync,
      )
    }

  suspend fun captureAllFromSystemClipboard(
    source: ClipSource = ClipSource.CLIPBOARD,
    foregroundPackageHint: String? = null,
    overrideSnapshots: List<SystemClipboardSnapshot>? = null,
    allowWhenAutoCaptureDisabled: Boolean = false,
  ): List<CaptureOutcome> =
    captureMutex.withLock {
      val settings = prefs.userSettings.first()
      if (!allowWhenAutoCaptureDisabled && !settings.autoCaptureOnForeground && !settings.instantAutoSave) {
        return@withLock listOf(CaptureOutcome.EmptyOrDisabled)
      }

      val snapshots = overrideSnapshots ?: clipboardHelper.readAllClipboardSnapshots()
      if (snapshots.isEmpty()) {
        return@withLock listOf(CaptureOutcome.EmptyOrDisabled)
      }

      var currentLastHash = prefs.getLastDetectedClipHashOnce()
      val outcomes = ArrayList<CaptureOutcome>(snapshots.size)
      for (snapshot in snapshots) {
        val outcome =
          captureSnapshotLocked(
            snapshot = snapshot,
            source = source,
            foregroundPackageHint = foregroundPackageHint,
            lastDetectedHash = currentLastHash,
            excludedSourceAppsCsv = settings.excludedSourceAppsCsv,
            autoDetectSensitivePatterns = settings.autoDetectSensitivePatterns,
            skipAutoSaveSensitive = settings.skipAutoSaveSensitive,
            blockSensitiveCloudSync = settings.blockSensitiveCloudSync,
          )
        outcomes.add(outcome)
        val trimmed = snapshot.text.trim()
        if (trimmed.isNotEmpty() && outcome !is CaptureOutcome.EmptyOrDisabled) {
          currentLastHash = cryptoManager.sha256(trimmed)
        }
      }
      outcomes
    }

  suspend fun captureSnippetDirectly(
    rawText: String,
    source: ClipSource = ClipSource.SHARE_SHEET,
    foregroundPackageHint: String? = null,
  ): CaptureOutcome =
    captureFromSystemClipboard(
      source = source,
      foregroundPackageHint = foregroundPackageHint,
      overrideSnapshot = SystemClipboardSnapshot(text = rawText),
      allowWhenAutoCaptureDisabled = true,
    )

  private suspend fun captureSnapshotLocked(
    snapshot: SystemClipboardSnapshot,
    source: ClipSource,
    foregroundPackageHint: String?,
    lastDetectedHash: String,
    excludedSourceAppsCsv: String,
    autoDetectSensitivePatterns: Boolean,
    skipAutoSaveSensitive: Boolean,
    blockSensitiveCloudSync: Boolean,
  ): CaptureOutcome {
    val text = snapshot.text.trim()
    if (text.isEmpty()) return CaptureOutcome.EmptyOrDisabled

    val hash = cryptoManager.sha256(text)
    if (hash == lastDetectedHash) {
      return CaptureOutcome.AlreadyCaptured
    }

    val excludedRules =
      excludedSourceAppsCsv
        .split(',')
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }

    val pkgName = (foregroundPackageHint ?: snapshot.sourcePackageHint)?.lowercase()
    val textLower = text.lowercase()
    if (
      excludedRules.any { rule ->
        (pkgName != null && pkgName.contains(rule)) || textLower.contains(rule)
      }
    ) {
      prefs.updateLastDetectedClipHash(hash)
      return CaptureOutcome.ExcludedByRule
    }

    val detection =
      if (autoDetectSensitivePatterns || snapshot.isSystemSensitive) {
        sensitiveDetector.analyze(text, snapshot.isSystemSensitive)
      } else {
        SensitiveAnalysis(false, null)
      }

    if (detection.isSensitive && skipAutoSaveSensitive) {
      prefs.updateLastDetectedClipHash(hash)
      return CaptureOutcome.SkippedSensitive
    }

    val tg = prefs.getTelegramConfigOnce()
    val dc = prefs.getDiscordConfigOnce()
    val gd = prefs.getGoogleDriveConfigOnce()

    val eligibleProviders = buildList {
      if (tg.enabled && tg.autoSync && tg.botToken.isNotBlank() && tg.chatId.isNotBlank()) {
        add(SyncProviderId.TELEGRAM)
      }
      if (
        dc.enabled &&
          dc.autoSync &&
          (dc.webhookUrl.isNotBlank() || (dc.botToken.isNotBlank() && dc.channelId.isNotBlank()))
      ) {
        add(SyncProviderId.DISCORD)
      }
      if (gd.enabled && gd.autoIncrementalBackup) {
        add(SyncProviderId.GOOGLE_DRIVE)
      }
    }

    val allowCloudSync = !(detection.isSensitive && blockSensitiveCloudSync)
    val initialSyncState =
      if (!allowCloudSync || eligibleProviders.isEmpty()) ClipSyncState.LOCAL_ONLY
      else ClipSyncState.PENDING

    val savedRes =
      saveClipUseCase(
        title = "",
        content = text,
        categoryId = null,
        tags = emptyList(),
        isSensitive = detection.isSensitive,
        source = source,
        syncStatus = initialSyncState,
      ) ?: return CaptureOutcome.EmptyOrDisabled

    prefs.updateLastDetectedClipHash(hash)

    val queuedProviders =
      if (allowCloudSync && eligibleProviders.isNotEmpty()) {
        for (provider in eligibleProviders) {
          syncQueueRepository.enqueueClip(
            clipId = savedRes.clipId,
            providerId = provider,
            operation = SyncQueueOperation.PUSH,
          )
        }
        syncEngine.enqueueImmediateSync()
        eligibleProviders
      } else {
        emptyList()
      }

    val clip = clipRepository.getClipById(savedRes.clipId)
      ?: ClipItem(
        id = savedRes.clipId,
        content = text,
        contentHash = hash,
        isSensitive = detection.isSensitive,
        source = source,
        syncStatus = initialSyncState,
      )

    return CaptureOutcome.Saved(
      clip = clip,
      wasDuplicateMerged = savedRes.wasDuplicateMerged,
      autoMarkedSensitive = detection.isSensitive,
      sensitiveReason = detection.reason,
      queuedProviders = queuedProviders,
    )
  }

  suspend fun enqueueClipForSync(clipId: Long, explicitProviders: List<SyncProviderId>? = null) {
    val clip = clipRepository.getClipById(clipId) ?: return
    val settings = prefs.userSettings.first()
    if (clip.isSensitive && settings.blockSensitiveCloudSync) return

    val tg = prefs.getTelegramConfigOnce()
    val dc = prefs.getDiscordConfigOnce()
    val gd = prefs.getGoogleDriveConfigOnce()

    val targets =
      explicitProviders
        ?: buildList {
          if (tg.enabled && tg.autoSync && tg.botToken.isNotBlank() && tg.chatId.isNotBlank()) {
            add(SyncProviderId.TELEGRAM)
          }
          if (
            dc.enabled &&
              dc.autoSync &&
              (dc.webhookUrl.isNotBlank() ||
                (dc.botToken.isNotBlank() && dc.channelId.isNotBlank()))
          ) {
            add(SyncProviderId.DISCORD)
          }
          if (gd.enabled) {
            add(SyncProviderId.GOOGLE_DRIVE)
          }
        }
    if (targets.isEmpty()) return

    for (provider in targets) {
      syncQueueRepository.enqueueClip(clipId, provider, SyncQueueOperation.PUSH)
    }
    clipRepository.updateClipSyncStatus(clipId, ClipSyncState.PENDING, null)
    syncEngine.enqueueImmediateSync()
  }
}
