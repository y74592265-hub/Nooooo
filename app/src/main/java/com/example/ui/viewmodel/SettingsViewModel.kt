package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.core.clipboard.QuickCaptureNotificationHelper
import com.example.core.clipboard.SystemClipboardHelper
import com.example.core.preferences.AppLanguage
import com.example.core.preferences.AppPreferencesRepository
import com.example.core.preferences.ThemeMode
import com.example.core.preferences.UserSettings
import com.example.domain.repository.ClipRepository
import com.example.domain.repository.SyncLogRepository
import com.example.ui.components.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel
@Inject
constructor(
  @ApplicationContext private val appContext: Context,
  private val prefs: AppPreferencesRepository,
  private val clipRepository: ClipRepository,
  private val syncLogRepository: SyncLogRepository,
  private val clipboardHelper: SystemClipboardHelper,
) : ViewModel() {

  val userSettings: StateFlow<UserSettings> =
    prefs.userSettings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())

  private val _events = MutableSharedFlow<UiText>(extraBufferCapacity = 8)
  val events: SharedFlow<UiText> = _events.asSharedFlow()

  fun setThemeMode(mode: ThemeMode) {
    viewModelScope.launch { prefs.updateThemeMode(mode) }
  }

  fun setLanguage(language: AppLanguage) {
    viewModelScope.launch {
      prefs.updateLanguage(language)
      val enabled = prefs.userSettings.first().quickCaptureNotificationEnabled
      if (enabled) {
        QuickCaptureNotificationHelper.showOrCancel(appContext, enabled = true, language = language)
      }
    }
  }

  fun setAppLockEnabled(enabled: Boolean) {
    viewModelScope.launch { prefs.updateAppLockEnabled(enabled) }
  }

  fun setAutoCapture(enabled: Boolean) {
    viewModelScope.launch { prefs.updateAutoCapture(enabled) }
  }

  fun setInstantAutoSave(enabled: Boolean) {
    viewModelScope.launch { prefs.updateInstantAutoSave(enabled) }
  }

  fun setQuickCaptureNotificationEnabled(enabled: Boolean) {
    viewModelScope.launch {
      prefs.updateQuickCaptureNotificationEnabled(enabled)
      val language = prefs.userSettings.first().language
      QuickCaptureNotificationHelper.showOrCancel(appContext, enabled = enabled, language = language)
    }
  }

  fun setMultiCopyTipDismissed(dismissed: Boolean) {
    viewModelScope.launch { prefs.updateMultiCopyTipDismissed(dismissed) }
  }

  fun emitMessage(message: String) {
    viewModelScope.launch { _events.emit(UiText.Dynamic(message)) }
  }

  fun setAutoDetectSensitivePatterns(enabled: Boolean) {
    viewModelScope.launch { prefs.updateAutoDetectSensitivePatterns(enabled) }
  }

  fun setBlockSensitiveCloudSync(enabled: Boolean) {
    viewModelScope.launch { prefs.updateBlockSensitiveCloudSync(enabled) }
  }

  fun setSkipAutoSaveSensitive(enabled: Boolean) {
    viewModelScope.launch { prefs.updateSkipAutoSaveSensitive(enabled) }
  }

  fun setExcludedSourceAppsCsv(csv: String) {
    viewModelScope.launch {
      prefs.updateExcludedSourceAppsCsv(csv)
      _events.emit(UiText.Resource(R.string.msg_exclusions_saved))
    }
  }

  fun setDeleteCloudOnLocalDelete(enabled: Boolean) {
    viewModelScope.launch { prefs.updateDeleteCloudOnLocalDelete(enabled) }
  }

  fun setStorageSoftQuotaMb(quotaMb: Int) {
    viewModelScope.launch { prefs.updateStorageSoftQuotaMb(quotaMb) }
  }

  fun setStorageWarnThresholds(warn1: Int, warn2: Int, warn3: Int) {
    viewModelScope.launch { prefs.updateStorageWarnThresholds(warn1, warn2, warn3) }
  }

  fun setRetentionDays(days: Int) {
    viewModelScope.launch {
      prefs.updateRetentionDays(days)
      if (days > 0) {
        val purged = clipRepository.purgeExpiredHistory(days)
        if (purged > 0) _events.emit(UiText.Resource(R.string.msg_cleaned_expired, listOf(purged)))
      }
    }
  }

  fun clearSystemClipboard() {
    clipboardHelper.clearSystemClipboard()
    viewModelScope.launch { _events.emit(UiText.Resource(R.string.msg_sys_clipboard_cleared)) }
  }

  fun wipeAllData() {
    viewModelScope.launch {
      clipRepository.wipeAllData()
      syncLogRepository.clearLogs()
      prefs.clearAllPreferences()
      QuickCaptureNotificationHelper.showOrCancel(appContext, enabled = false)
      clipboardHelper.clearSystemClipboard()
      _events.emit(UiText.Resource(R.string.msg_all_data_wiped))
    }
  }
}
