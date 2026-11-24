package com.example

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.core.clipboard.QuickCaptureNotificationHelper
import com.example.core.preferences.AppPreferencesRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class SmartClipboardApp : Application(), Configuration.Provider {

  @Inject lateinit var workerFactory: HiltWorkerFactory
  @Inject lateinit var prefs: AppPreferencesRepository

  private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onCreate() {
    super.onCreate()
    appScope.launch {
      runCatching {
        val settings = prefs.userSettings.first()
        if (settings.quickCaptureNotificationEnabled) {
          QuickCaptureNotificationHelper.showOrCancel(
            context = this@SmartClipboardApp,
            enabled = true,
            language = settings.language,
          )
        }
      }
    }
  }

  override val workManagerConfiguration: Configuration
    get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
