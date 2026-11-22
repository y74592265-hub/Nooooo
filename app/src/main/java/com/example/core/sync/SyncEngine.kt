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
import com.example.domain.model.ClipItem
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

  private val syncMutex = Mutex()
  private val _state = MutableStateFlow(SyncEngineState())
  val state: StateFlow<SyncEngineState> = _state.asStateFlow()

  fun isNetworkAvailable(): Boolean =
    runCatching {
      val cm =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
      val network = cm.activeNetwork ?: return false
      val caps = cm.getNetworkCapabilities(network) ?: return false
      caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

  suspend fun syncAll(pushRecentLimit: Int = 10): Boolean {
    if (!syncMutex.tryLock()) {
      // Single-flight guard: a sync is already in progress, ignore duplicate rapid trigger cleanly
      return true
    }

    var anyRetryable = false
    try {
      if (!isNetworkAvailable()) {
        _state.value =
          SyncEngineState(
            isSyncing = false,
            lastOverallStatus = SyncStatus.ERROR,
            lastMessage = "Offline — local clipboard remains active; sync will resume when online.",
          )
        return false
      }

      _state.value =
        SyncEngineState(
          isSyncing = true,
          lastOverallStatus = SyncStatus.SYNCING,
          lastMessage = "Syncing…",
        )

      var anyError = false
      var configuredCount = 0
      var totalItemsSynced = 0

      categoryRepository.ensureDefaultCategories()
      val categoriesByName =
        categoryRepository.getAllCategoriesOnce().associateBy { it.name.lowercase() }
      val recentClips = clipRepository.getAllActiveClipsOnce().take(pushRecentLimit)

      for (provider in providers) {
        val isReady =
          try {
            provider.isConfigured()
          } catch (ce: CancellationException) {
            throw ce
          } catch (e: Exception) {
            anyError = true
            syncLogRepository.addLog(
              SyncLog(
                provider = provider.providerId,
                direction = SyncDirection.PULL,
                status = SyncStatus.ERROR,
                message = e.localizedMessage ?: "Provider configuration check failed",
              )
            )
            false
          }
        if (!isReady) continue
        configuredCount++

        try {
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
                    clipRepository.updateRemoteId(
                      c.id,
                      "pushed:${provider.providerId.id}:${c.contentHash.take(8)}",
                    )
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
        } catch (ce: CancellationException) {
          throw ce
        } catch (e: Exception) {
          anyError = true
          syncLogRepository.addLog(
            SyncLog(
              provider = provider.providerId,
              direction = SyncDirection.PUSH,
              status = SyncStatus.ERROR,
              message = e.localizedMessage ?: "Unexpected provider sync error",
            )
          )
        }
      }

      val status = if (!anyError) SyncStatus.SUCCESS else SyncStatus.PARTIAL_ERROR
      prefs.updateLastGlobalSyncAt(System.currentTimeMillis())
      _state.value =
        SyncEngineState(
          isSyncing = false,
          lastOverallStatus = status,
          lastMessage =
            when {
              configuredCount == 0 -> "No cloud providers enabled yet (open Integrations to connect)"
              !anyError -> "Sync complete ($totalItemsSynced items)"
              else -> "Completed with provider errors (see Sync Logs)"
            },
        )
    } catch (ce: CancellationException) {
      _state.value =
        SyncEngineState(
          isSyncing = false,
          lastOverallStatus = SyncStatus.IDLE,
          lastMessage = "Sync canceled",
        )
      throw ce
    } catch (e: Exception) {
      anyRetryable = true
      _state.value =
        SyncEngineState(
          isSyncing = false,
          lastOverallStatus = SyncStatus.ERROR,
          lastMessage = e.localizedMessage ?: "Sync error",
        )
    } finally {
      if (_state.value.isSyncing) {
        _state.value = _state.value.copy(isSyncing = false)
      }
      syncMutex.unlock()
    }
    return !anyRetryable
  }

  suspend fun syncSingleProvider(providerId: SyncProviderId): ProviderResult<String> {
    return syncMutex.withLock {
      if (!isNetworkAvailable()) {
        return@withLock ProviderResult.Error("No internet connection. Local clipboard is unaffected.", true)
      }
      val provider = providers.firstOrNull { it.providerId == providerId }
        ?: return@withLock ProviderResult.Error("Provider not registered.")
      try {
        if (!provider.isConfigured()) {
          return@withLock ProviderResult.Error("${providerId.displayName} is not enabled or configured.")
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
          return@withLock pullRes
        }

        val pushCandidates = clipRepository.getAllActiveClipsOnce().take(10)
        when (val pushRes = provider.pushClips(pushCandidates)) {
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
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: Exception) {
        val msg = e.localizedMessage ?: "Unexpected sync error"
        runCatching {
          syncLogRepository.addLog(
            SyncLog(
              provider = providerId,
              direction = SyncDirection.PUSH,
              status = SyncStatus.ERROR,
              message = msg,
            )
          )
        }
        ProviderResult.Error(msg)
      }
    }
  }

  suspend fun pushSingleClip(clip: ClipItem, providerId: SyncProviderId): ProviderResult<String> {
    if (clip.isSensitive) {
      return ProviderResult.Error("Sensitive clips are protected and cannot be auto-pushed.")
    }
    return syncMutex.withLock {
      if (!isNetworkAvailable()) {
        return@withLock ProviderResult.Error("No internet connection. Local clipboard is unaffected.", true)
      }
      val provider = providers.firstOrNull { it.providerId == providerId }
        ?: return@withLock ProviderResult.Error("Provider not registered.")
      try {
        if (!provider.isConfigured()) {
          return@withLock ProviderResult.Error("${providerId.displayName} is not enabled or configured.")
        }
        when (val pushRes = provider.pushClips(listOf(clip))) {
          is ProviderResult.Success -> {
            if (providerId != SyncProviderId.GOOGLE_DRIVE && clip.id > 0L) {
              clipRepository.updateRemoteId(
                clip.id,
                "pushed:${providerId.id}:${clip.contentHash.take(8)}",
              )
            }
            syncLogRepository.addLog(
              SyncLog(
                provider = providerId,
                direction = SyncDirection.PUSH,
                status = SyncStatus.SUCCESS,
                message = "Pushed 1 clip",
                itemsCount = pushRes.data,
              )
            )
            prefs.updateLastGlobalSyncAt(System.currentTimeMillis())
            ProviderResult.Success("Sent clip to ${providerId.displayName}")
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
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: Exception) {
        val msg = e.localizedMessage ?: "Unexpected push error"
        ProviderResult.Error(msg)
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
