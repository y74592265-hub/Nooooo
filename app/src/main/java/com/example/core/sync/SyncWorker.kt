package com.example.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.core.preferences.AppPreferencesRepository
import com.example.domain.repository.ClipRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class SyncWorker
@AssistedInject
constructor(
  @Assisted appContext: Context,
  @Assisted params: WorkerParameters,
  private val syncEngine: SyncEngine,
  private val clipRepository: ClipRepository,
  private val prefs: AppPreferencesRepository,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    val settings = prefs.userSettings.first()
    if (settings.historyRetentionDays > 0) {
      clipRepository.purgeExpiredHistory(settings.historyRetentionDays)
    }
    val completedWithoutRetryableError = syncEngine.syncAll()
    return if (completedWithoutRetryableError) {
      Result.success()
    } else if (runAttemptCount < 4) {
      Result.retry()
    } else {
      Result.failure()
    }
  }
}
