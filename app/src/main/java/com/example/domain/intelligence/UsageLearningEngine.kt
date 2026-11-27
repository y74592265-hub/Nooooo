package com.example.domain.intelligence

import com.example.core.preferences.AppPreferencesRepository
import com.example.core.security.IntelligencePrivacyGate
import com.example.data.local.ClipDao
import com.example.data.local.ClipTransitionDao
import com.example.domain.model.ClipTransitionEdge
import com.example.domain.model.FieldContextSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class UsageEvent(
  val clipId: Long,
  val isSensitive: Boolean,
  val signal: FieldContextSignal?,
  val timestampMs: Long,
)

/**
 * Asynchronous non-blocking Usage Learning Engine.
 * - Never blocks IME paste or UI interactions (bounded Channel with DROP_OLDEST).
 * - Respects IntelligencePrivacyGate strictly: ignores sensitive clips, secret fields,
 *   IME_FLAG_NO_PERSONALIZED_LEARNING, excluded packages, and user toggle.
 */
@Singleton
class UsageLearningEngine
@Inject
constructor(
  private val clipDao: ClipDao,
  private val transitionDao: ClipTransitionDao,
  private val preferencesRepository: AppPreferencesRepository,
) {

  companion object {
    const val TRANSITION_WINDOW_MS = 120_000L // 2 minutes co-usage window
    const val TRANSITION_RETENTION_MS = 60L * 24L * 60L * 60L * 1000L // 60 days
  }

  private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val events = Channel<UsageEvent>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  private val lastUsedClipIdRef = AtomicLong(0L)
  private val lastUsedAtRef = AtomicLong(0L)

  init {
    engineScope.launch {
      for (event in events) {
        runCatching { processEvent(event) }
      }
    }
  }

  fun currentLastUsedClipId(): Long? = lastUsedClipIdRef.get().takeIf { it > 0L }

  fun recordUsage(
    clipId: Long,
    isSensitive: Boolean,
    signal: FieldContextSignal? = null,
    nowMs: Long = System.currentTimeMillis(),
  ) {
    if (clipId <= 0L) return
    val settings = preferencesRepository.latestSettingsSnapshot
    if (!IntelligencePrivacyGate.canLearnClipUsage(isSensitive, signal, settings)) {
      return
    }
    events.trySend(
      UsageEvent(
        clipId = clipId,
        isSensitive = isSensitive,
        signal = signal,
        timestampMs = nowMs,
      )
    )
  }

  /**
   * Synchronous/suspend entry point primarily for deterministic tests and explicit flush paths.
   */
  suspend fun recordUsageAndProcess(
    clipId: Long,
    isSensitive: Boolean,
    signal: FieldContextSignal? = null,
    nowMs: Long = System.currentTimeMillis(),
  ) {
    if (clipId <= 0L) return
    val settings = preferencesRepository.latestSettingsSnapshot
    if (!IntelligencePrivacyGate.canLearnClipUsage(isSensitive, signal, settings)) {
      return
    }
    withContext(Dispatchers.IO) {
      processEvent(
        UsageEvent(
          clipId = clipId,
          isSensitive = isSensitive,
          signal = signal,
          timestampMs = nowMs,
        )
      )
    }
  }

  private suspend fun processEvent(event: UsageEvent) {
    val settings = preferencesRepository.latestSettingsSnapshot
    if (!IntelligencePrivacyGate.canLearnClipUsage(event.isSensitive, event.signal, settings)) {
      return
    }
    clipDao.incrementUsage(event.clipId, event.timestampMs)

    val prevId = lastUsedClipIdRef.get()
    val prevAt = lastUsedAtRef.get()
    if (
      prevId > 0L &&
        prevId != event.clipId &&
        event.timestampMs - prevAt in 0L..TRANSITION_WINDOW_MS
    ) {
      transitionDao.recordTransition(
        fromClipId = prevId,
        toClipId = event.clipId,
        nowMs = event.timestampMs,
      )
    }
    lastUsedClipIdRef.set(event.clipId)
    lastUsedAtRef.set(event.timestampMs)
  }

  suspend fun getTransitionsForCurrentAnchor(limit: Int = 6): List<ClipTransitionEdge> {
    val anchor = lastUsedClipIdRef.get()
    if (anchor <= 0L) return emptyList()
    return withContext(Dispatchers.IO) {
      transitionDao.getTopTransitionsFrom(anchor, limit).map {
        ClipTransitionEdge(
          fromClipId = it.fromClipId,
          toClipId = it.toClipId,
          weight = it.weight,
          hitCount = it.hitCount,
          lastTriggeredAt = it.lastTriggeredAt,
        )
      }
    }
  }

  suspend fun pruneStaleTransitions(nowMs: Long = System.currentTimeMillis()) {
    withContext(Dispatchers.IO) {
      transitionDao.pruneStaleTransitions(cutoffTimestamp = nowMs - TRANSITION_RETENTION_MS)
    }
  }

  suspend fun clearAllLearningData() {
    lastUsedClipIdRef.set(0L)
    lastUsedAtRef.set(0L)
    withContext(Dispatchers.IO) {
      transitionDao.clearAllTransitions()
      clipDao.resetAllUsageStats()
    }
  }
}
