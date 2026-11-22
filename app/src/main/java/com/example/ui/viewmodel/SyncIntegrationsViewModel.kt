package com.example.ui.viewmodel

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.core.preferences.AppPreferencesRepository
import com.example.core.sync.SyncEngine
import com.example.core.sync.SyncEngineState
import com.example.domain.model.DiscordConfig
import com.example.domain.model.GoogleDriveConfig
import com.example.domain.model.SyncLog
import com.example.domain.model.SyncProviderId
import com.example.domain.model.TelegramConfig
import com.example.domain.repository.SyncLogRepository
import com.example.domain.usecase.ExportFormat
import com.example.domain.usecase.ExportImportUseCase
import com.example.integrations.ProviderResult
import com.example.integrations.discord.DiscordProvider
import com.example.integrations.gdrive.DriveAuthState
import com.example.integrations.gdrive.DriveFileDto
import com.example.integrations.gdrive.GoogleDriveAuthHelper
import com.example.integrations.gdrive.GoogleDriveProvider
import com.example.integrations.telegram.TelegramProvider
import com.example.ui.components.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncIntegrationsViewModel
@Inject
constructor(
  private val prefs: AppPreferencesRepository,
  private val syncEngine: SyncEngine,
  private val syncLogRepository: SyncLogRepository,
  private val telegramProvider: TelegramProvider,
  private val discordProvider: DiscordProvider,
  private val googleDriveProvider: GoogleDriveProvider,
  private val driveAuthHelper: GoogleDriveAuthHelper,
  private val exportImportUseCase: ExportImportUseCase,
) : ViewModel() {

  val telegramConfig: StateFlow<TelegramConfig> =
    prefs.telegramConfig.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TelegramConfig())

  val discordConfig: StateFlow<DiscordConfig> =
    prefs.discordConfig.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DiscordConfig())

  val googleDriveConfig: StateFlow<GoogleDriveConfig> =
    prefs.googleDriveConfig.stateIn(
      viewModelScope,
      SharingStarted.WhileSubscribed(5000),
      GoogleDriveConfig(),
    )

  val syncState: StateFlow<SyncEngineState> = syncEngine.state

  val syncLogs: StateFlow<List<SyncLog>> =
    syncLogRepository
      .observeRecentLogs()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val lastGlobalSyncAt: StateFlow<Long?> =
    prefs.lastGlobalSyncAt.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

  val userSettings =
    prefs.userSettings.stateIn(
      viewModelScope,
      SharingStarted.WhileSubscribed(5000),
      com.example.core.preferences.UserSettings(),
    )

  private val _driveBackups = MutableStateFlow<List<DriveFileDto>>(emptyList())
  val driveBackups: StateFlow<List<DriveFileDto>> = _driveBackups.asStateFlow()

  private val _driveFolders = MutableStateFlow<List<DriveFileDto>>(emptyList())
  val driveFolders: StateFlow<List<DriveFileDto>> = _driveFolders.asStateFlow()

  private val _isBusy = MutableStateFlow(false)
  val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

  private val _pendingConsentIntent = MutableSharedFlow<android.app.PendingIntent>()
  val pendingConsentIntent: SharedFlow<android.app.PendingIntent> = _pendingConsentIntent.asSharedFlow()

  private val _statusMessages = MutableSharedFlow<UiText>(extraBufferCapacity = 8)
  val statusMessages: SharedFlow<UiText> = _statusMessages.asSharedFlow()

  private suspend inline fun runBusyAction(crossinline block: suspend () -> Unit) {
    if (_isBusy.value) return
    _isBusy.value = true
    try {
      block()
    } catch (ce: CancellationException) {
      throw ce
    } catch (e: Exception) {
      _statusMessages.tryEmit(UiText.Dynamic(e.localizedMessage ?: "Operation failed"))
    } finally {
      _isBusy.value = false
    }
  }

  fun saveTelegramConfig(config: TelegramConfig) {
    viewModelScope.launch {
      prefs.saveTelegramConfig(config)
      _statusMessages.emit(UiText.Resource(R.string.msg_telegram_saved))
    }
  }

  fun testTelegramConnection() {
    viewModelScope.launch {
      runBusyAction {
        when (val res = telegramProvider.testConnection()) {
          is ProviderResult.Success -> _statusMessages.emit(UiText.Dynamic(res.data))
          is ProviderResult.Error -> _statusMessages.emit(UiText.Dynamic(res.message))
        }
      }
    }
  }

  fun detectTelegramChatId(botToken: String) {
    viewModelScope.launch {
      runBusyAction {
        when (val res = telegramProvider.detectChatIdFromUpdates(botToken)) {
          is ProviderResult.Success -> {
            val cur = prefs.getTelegramConfigOnce()
            prefs.saveTelegramConfig(cur.copy(botToken = botToken.trim(), chatId = res.data))
            _statusMessages.emit(UiText.Resource(R.string.msg_telegram_chat_detected, listOf(res.data)))
          }
          is ProviderResult.Error -> _statusMessages.emit(UiText.Dynamic(res.message))
        }
      }
    }
  }

  fun saveDiscordConfig(config: DiscordConfig) {
    viewModelScope.launch {
      prefs.saveDiscordConfig(config)
      _statusMessages.emit(UiText.Resource(R.string.msg_discord_saved))
    }
  }

  fun testDiscordConnection() {
    viewModelScope.launch {
      runBusyAction {
        when (val res = discordProvider.testConnection()) {
          is ProviderResult.Success -> _statusMessages.emit(UiText.Dynamic(res.data))
          is ProviderResult.Error -> _statusMessages.emit(UiText.Dynamic(res.message))
        }
      }
    }
  }

  fun connectGoogleDrive(includeVisibleFolderScope: Boolean) {
    viewModelScope.launch {
      runBusyAction {
        when (val auth = driveAuthHelper.requestAuthorization(includeVisibleFolderScope)) {
          is DriveAuthState.Authorized -> verifyDriveAfterAuth()
          is DriveAuthState.NeedsUserConsent -> _pendingConsentIntent.emit(auth.pendingIntent)
          is DriveAuthState.Failed -> _statusMessages.emit(UiText.Dynamic(auth.message))
        }
      }
    }
  }

  fun onDriveConsentResult(intent: Intent?) {
    viewModelScope.launch {
      runBusyAction {
        when (val res = driveAuthHelper.handleAuthorizationResultIntent(intent)) {
          is DriveAuthState.Authorized -> verifyDriveAfterAuth()
          is DriveAuthState.Failed -> _statusMessages.emit(UiText.Dynamic(res.message))
          is DriveAuthState.NeedsUserConsent -> Unit
        }
      }
    }
  }

  private suspend fun verifyDriveAfterAuth() {
    when (val testRes = googleDriveProvider.testConnection()) {
      is ProviderResult.Success -> {
        _statusMessages.emit(UiText.Dynamic(testRes.data))
        when (val backups = googleDriveProvider.listBackups()) {
          is ProviderResult.Success -> _driveBackups.value = backups.data
          is ProviderResult.Error -> _statusMessages.emit(UiText.Dynamic(backups.message))
        }
      }
      is ProviderResult.Error -> _statusMessages.emit(UiText.Dynamic(testRes.message))
    }
  }

  fun saveDriveConfig(config: GoogleDriveConfig) {
    viewModelScope.launch {
      prefs.saveGoogleDriveConfig(config)
      _statusMessages.emit(UiText.Resource(R.string.msg_drive_saved))
    }
  }

  fun disconnectGoogleDrive() {
    viewModelScope.launch {
      driveAuthHelper.clearCachedToken()
      prefs.saveGoogleDriveConfig(GoogleDriveConfig())
      _driveBackups.value = emptyList()
      _driveFolders.value = emptyList()
      _statusMessages.emit(UiText.Resource(R.string.msg_drive_disconnected))
    }
  }

  fun refreshDriveBackups() {
    viewModelScope.launch {
      runBusyAction {
        when (val res = googleDriveProvider.listBackups()) {
          is ProviderResult.Success -> _driveBackups.value = res.data
          is ProviderResult.Error -> _statusMessages.emit(UiText.Dynamic(res.message))
        }
      }
    }
  }

  fun loadDriveFolders() {
    viewModelScope.launch {
      runBusyAction {
        when (val res = googleDriveProvider.fetchDriveFolders()) {
          is ProviderResult.Success -> _driveFolders.value = res.data
          is ProviderResult.Error -> _statusMessages.emit(UiText.Dynamic(res.message))
        }
      }
    }
  }

  fun createDriveBackupFolder(name: String) {
    viewModelScope.launch {
      runBusyAction {
        when (val res = googleDriveProvider.createBackupFolder(name)) {
          is ProviderResult.Success -> {
            _statusMessages.emit(
              UiText.Resource(R.string.msg_drive_folder_created, listOf(res.data.name))
            )
            when (val folders = googleDriveProvider.fetchDriveFolders()) {
              is ProviderResult.Success -> _driveFolders.value = folders.data
              is ProviderResult.Error -> _statusMessages.emit(UiText.Dynamic(folders.message))
            }
          }
          is ProviderResult.Error -> _statusMessages.emit(UiText.Dynamic(res.message))
        }
      }
    }
  }

  fun backupToDriveNow(passphrase: String?) {
    viewModelScope.launch {
      runBusyAction {
        when (val res = googleDriveProvider.backupNow(passphrase)) {
          is ProviderResult.Success -> {
            _statusMessages.emit(
              UiText.Resource(R.string.msg_drive_backup_saved, listOf(res.detail))
            )
            when (val backups = googleDriveProvider.listBackups()) {
              is ProviderResult.Success -> _driveBackups.value = backups.data
              is ProviderResult.Error -> Unit
            }
          }
          is ProviderResult.Error -> _statusMessages.emit(UiText.Dynamic(res.message))
        }
      }
    }
  }

  fun restoreFromDrive(fileId: String, passphrase: String?) {
    viewModelScope.launch {
      runBusyAction {
        when (val res = googleDriveProvider.restoreBackup(fileId, passphrase)) {
          is ProviderResult.Success -> {
            val s = res.data
            _statusMessages.emit(
              UiText.Resource(
                R.string.msg_restore_summary,
                listOf(s.importedCount, s.deduplicatedCount, s.checksumVerified.toString()),
              )
            )
          }
          is ProviderResult.Error -> _statusMessages.emit(UiText.Dynamic(res.message))
        }
      }
    }
  }

  fun syncAllNow() {
    if (syncEngine.state.value.isSyncing || _isBusy.value) return
    viewModelScope.launch {
      _isBusy.value = true
      try {
        syncEngine.syncAll()
        val msg = syncEngine.state.value.lastMessage
        if (msg.isNotBlank()) {
          _statusMessages.emit(UiText.Dynamic(msg))
        }
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: Exception) {
        _statusMessages.tryEmit(UiText.Dynamic(e.localizedMessage ?: "Sync error"))
      } finally {
        _isBusy.value = false
      }
    }
  }

  fun syncProviderNow(providerId: SyncProviderId) {
    viewModelScope.launch {
      runBusyAction {
        when (val res = syncEngine.syncSingleProvider(providerId)) {
          is ProviderResult.Success ->
            _statusMessages.emit(UiText.Dynamic("${providerId.displayName}: ${res.data}"))
          is ProviderResult.Error ->
            _statusMessages.emit(UiText.Dynamic("${providerId.displayName}: ${res.message}"))
        }
      }
    }
  }

  fun toggleAutoSync(enabled: Boolean, intervalHours: Int = 6) {
    viewModelScope.launch {
      try {
        prefs.updateAutoSync(enabled, intervalHours)
        syncEngine.schedulePeriodicSync(enabled, intervalHours)
        _statusMessages.emit(
          if (enabled) UiText.Resource(R.string.msg_autosync_scheduled, listOf(intervalHours))
          else UiText.Resource(R.string.msg_autosync_paused)
        )
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: Exception) {
        _statusMessages.tryEmit(UiText.Dynamic(e.localizedMessage ?: "Auto-sync update failed"))
      }
    }
  }

  fun clearSyncLogs() {
    viewModelScope.launch { syncLogRepository.clearLogs() }
  }

  suspend fun buildLocalExportContent(format: ExportFormat, passphrase: String? = null): String =
    when (format) {
      ExportFormat.JSON -> exportImportUseCase.exportAsJson()
      ExportFormat.CSV -> exportImportUseCase.exportAsCsv()
      ExportFormat.TXT -> exportImportUseCase.exportAsTxt()
      ExportFormat.ENCRYPTED_BACKUP -> {
        require(!passphrase.isNullOrBlank()) { "Passphrase required for encrypted backup" }
        exportImportUseCase.exportEncryptedBackup(passphrase)
      }
    }

  fun restoreLocalFile(rawContent: String, passphrase: String?) {
    viewModelScope.launch {
      runBusyAction {
        runCatching { exportImportUseCase.restoreFromRawContent(rawContent, passphrase) }
          .onSuccess { s ->
            _statusMessages.emit(
              UiText.Resource(
                R.string.msg_restore_summary,
                listOf(s.importedCount, s.deduplicatedCount, s.checksumVerified.toString()),
              )
            )
          }
          .onFailure { e ->
            if (e is CancellationException) throw e
            _statusMessages.emit(
              UiText.Resource(R.string.msg_restore_failed, listOf(e.localizedMessage ?: ""))
            )
          }
      }
    }
  }
}
