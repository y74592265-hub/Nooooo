package com.example.core.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.core.preferences.AppPreferencesRepository
import com.example.domain.model.SyncDirection
import com.example.domain.model.SyncLog
import com.example.domain.model.SyncProviderId
import com.example.domain.model.SyncStatus
import com.example.domain.repository.CategoryRepository
import com.example.domain.repository.ClipRepository
import com.example.domain.repository.SyncLogRepository
import com.example.integrations.IntegrationProvider
import com.example.integrations.ProviderResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class SyncEngineState(
  val isSyncing: Boolean = false,
  val lastOverallStatus: SyncStatus = SyncStatus.IDLE,
  val lastMessage: String = "",
)

@Singleton
class SyncEngine
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val providers: Set<@JvmSuppressWildcards IntegrationProvider>,
  private val clipRepository: ClipRepository,
  private val categoryRepository: CategoryRepository,
  private val syncLogRepository: SyncLogRepository,
  private val prefs: AppPreferencesRepository,
) {
  companion object {
    private const val PERIODIC_SYNC_WORK = "smart_clipboard_periodic_sync"
    private const val IMMEDIATE_SYNC_WORK = "smart_clipboard_immediate_sync"
  }

  private val _state = MutableStateFlow(SyncEngineState())
  val state: StateFlow<SyncEngineState> = _state.asStateFlow()

  fun isNetworkAvailable(): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
  }

  suspend fun syncAll(pushRecentLimit: Int = 10): Boolean {
    if (!isNetworkAvailable()) {
      _state.value =
        SyncEngineState(
          isSyncing = false,
          lastOverallStatus = SyncStatus.ERROR,
          lastMessage = "Offline — local clipboard remains active; sync will resume when online.",
        )
      return false
    }

    _state.value = SyncEngineState(isSyncing = true, lastOverallStatus = SyncStatus.SYNCING, lastMessage = "Syncing…")
    var anyError = false
    var anyRetryable = false
    var totalItemsSynced = 0

    try {
      categoryRepository.ensureDefaultCategories()
      val categoriesByName =
        categoryRepository.getAllCategoriesOnce().associateBy { it.name.lowercase() }
      val recentClips = clipRepository.getAllActiveClipsOnce().take(pushRecentLimit)

      for (provider in providers) {
        if (!provider.isConfigured()) continue

        // 1. Pull incoming items safely (per-provider isolation)
        when (val pullRes = provider.pullClips()) {
          is ProviderResult.Success -> {
            var pulledCount = 0
            for (pulled in pullRes.data) {
              val existingByRemote = clipRepository.findByRemoteId(pulled.remoteId)
              if (existingByRemote == null) {
                val catId = pulled.categoryHint?.let { categoriesByName[it.lowercase()]?.id }
                clipRepository.saveNewOrDeduplicate(
                  title = pulled.title,
                  content = pulled.content,
                  categoryId = catId,
                  tags = pulled.tags,
                  source = pulled.source,
                  remoteId = pulled.remoteId,
                )
                pulledCount++
              }
            }
            totalItemsSynced += pulledCount
            if (pulledCount > 0 || pullRes.detail.isBlank()) {
              syncLogRepository.addLog(
                SyncLog(
                  provider = provider.providerId,
                  direction = SyncDirection.PULL,
                  status = SyncStatus.SUCCESS,
                  message = "Pulled $pulledCount new items",
                  itemsCount = pulledCount,
                )
              )
            }
          }
          is ProviderResult.Error -> {
            anyError = true
            if (pullRes.isRetryable) anyRetryable = true
            syncLogRepository.addLog(
              SyncLog(
                provider = provider.providerId,
                direction = SyncDirection.PULL,
                status = SyncStatus.ERROR,
                message = pullRes.message,
              )
            )
          }
        }

        // 2. Push un-synced local clips safely
        val pushCandidates =
          if (provider.providerId == SyncProviderId.GOOGLE_DRIVE) {
            recentClips
          } else {
            recentClips.filter { it.remoteId == null && !it.isSensitive }
          }

        if (pushCandidates.isNotEmpty()) {
          when (val pushRes = provider.pushClips(pushCandidates)) {
            is ProviderResult.Success -> {
              totalItemsSynced += pushRes.data
              if (provider.providerId != SyncProviderId.GOOGLE_DRIVE) {
                for (c in pushCandidates) {
                  clipRepository.updateClip(c.copy(remoteId = "pushed:${provider.providerId.id}:${c.contentHash.take(8)}"))
                }
              }
              syncLogRepository.addLog(
                SyncLog(
                  provider = provider.providerId,
                  direction = SyncDirection.PUSH,
                  status = SyncStatus.SUCCESS,
                  message = "Synced ${pushRes.data} clips",
                  itemsCount = pushRes.data,
                )
              )
            }
            is ProviderResult.Error -> {
              anyError = true
              if (pushRes.isRetryable) anyRetryable = true
              syncLogRepository.addLog(
                SyncLog(
                  provider = provider.providerId,
                  direction = SyncDirection.PUSH,
                  status = SyncStatus.ERROR,
                  message = pushRes.message,
                )
              )
            }
          }
        }
      }

      val status =
        if (!anyError) SyncStatus.SUCCESS else SyncStatus.PARTIAL_ERROR
      prefs.updateLastGlobalSyncAt(System.currentTimeMillis())
      _state.value =
        SyncEngineState(
          isSyncing = false,
          lastOverallStatus = status,
          lastMessage =
            if (!anyError) "Sync complete ($totalItemsSynced items)"
            else "Completed with provider errors (see Sync Logs)",
        )
    } catch (e: Exception) {
      anyError = true
      _state.value =
        SyncEngineState(
          isSyncing = false,
          lastOverallStatus = SyncStatus.ERROR,
          lastMessage = e.localizedMessage ?: "Sync error",
        )
    }
    return !anyRetryable
  }

  suspend fun syncSingleProvider(providerId: SyncProviderId): ProviderResult<String> {
    if (!isNetworkAvailable()) {
      return ProviderResult.Error("No internet connection. Local clipboard is unaffected.", true)
    }
    val provider = providers.firstOrNull { it.providerId == providerId }
      ?: return ProviderResult.Error("Provider not registered.")
    if (!provider.isConfigured()) {
      return ProviderResult.Error("${providerId.displayName} is not enabled or configured.")
    }

    val pullRes = provider.pullClips()
    var pulledCount = 0
    if (pullRes is ProviderResult.Success) {
      for (pulled in pullRes.data) {
        if (clipRepository.findByRemoteId(pulled.remoteId) == null) {
          clipRepository.saveNewOrDeduplicate(
            title = pulled.title,
            content = pulled.content,
            tags = pulled.tags,
            source = pulled.source,
            remoteId = pulled.remoteId,
          )
          pulledCount++
        }
      }
      syncLogRepository.addLog(
        SyncLog(
          provider = providerId,
          direction = SyncDirection.PULL,
          status = SyncStatus.SUCCESS,
          message = "Pulled $pulledCount new items",
          itemsCount = pulledCount,
        )
      )
    } else if (pullRes is ProviderResult.Error) {
      syncLogRepository.addLog(
        SyncLog(
          provider = providerId,
          direction = SyncDirection.PULL,
          status = SyncStatus.ERROR,
          message = pullRes.message,
        )
      )
      return pullRes
    }

    val pushCandidates = clipRepository.getAllActiveClipsOnce().take(10)
    return when (val pushRes = provider.pushClips(pushCandidates)) {
      is ProviderResult.Success -> {
        syncLogRepository.addLog(
          SyncLog(
            provider = providerId,
            direction = SyncDirection.PUSH,
            status = SyncStatus.SUCCESS,
            message = "Pushed ${pushRes.data} clips, pulled $pulledCount",
            itemsCount = pushRes.data + pulledCount,
          )
        )
        prefs.updateLastGlobalSyncAt(System.currentTimeMillis())
        ProviderResult.Success("Synced ${pushRes.data} outgoing, $pulledCount incoming")
      }
      is ProviderResult.Error -> {
        syncLogRepository.addLog(
          SyncLog(
            provider = providerId,
            direction = SyncDirection.PUSH,
            status = SyncStatus.ERROR,
            message = pushRes.message,
          )
        )
        pushRes
      }
    }
  }

  fun schedulePeriodicSync(enabled: Boolean, intervalHours: Int = 6) {
    val wm = WorkManager.getInstance(context)
    if (!enabled) {
      wm.cancelUniqueWork(PERIODIC_SYNC_WORK)
      return
    }
    val constraints =
      Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    val request =
      PeriodicWorkRequestBuilder<SyncWorker>(intervalHours.toLong().coerceAtLeast(1L), TimeUnit.HOURS)
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
        .build()
    wm.enqueueUniquePeriodicWork(
      PERIODIC_SYNC_WORK,
      ExistingPeriodicWorkPolicy.UPDATE,
      request,
    )
  }

  fun enqueueImmediateSync() {
    val constraints =
      Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    val request =
      OneTimeWorkRequestBuilder<SyncWorker>()
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()
    WorkManager.getInstance(context)
      .enqueueUniqueWork(IMMEDIATE_SYNC_WORK, ExistingWorkPolicy.REPLACE, request)
  }
}
