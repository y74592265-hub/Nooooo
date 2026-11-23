package com.example.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.security.KeystoreCryptoManager
import com.example.domain.model.DiscordConfig
import com.example.domain.model.GoogleDriveConfig
import com.example.domain.model.TelegramConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.smartClipboardDataStore: DataStore<Preferences> by
  preferencesDataStore(name = "smart_clipboard_prefs")

enum class ThemeMode {
  SYSTEM,
  LIGHT,
  DARK,
}

enum class AppLanguage(val tag: String) {
  SYSTEM("system"),
  ENGLISH("en"),
  ARABIC("ar"),
}

data class UserSettings(
  val themeMode: ThemeMode = ThemeMode.SYSTEM,
  val language: AppLanguage = AppLanguage.ARABIC,
  val appLockEnabled: Boolean = false,
  val autoCaptureOnForeground: Boolean = true,
  val instantAutoSave: Boolean = true,
  val autoDetectSensitivePatterns: Boolean = true,
  val blockSensitiveCloudSync: Boolean = true,
  val skipAutoSaveSensitive: Boolean = false,
  val excludedSourceAppsCsv: String = "",
  val deleteCloudOnLocalDelete: Boolean = false,
  val historyRetentionDays: Int = 30, // 0 = forever
  val autoSyncEnabled: Boolean = false,
  val autoSyncIntervalHours: Int = 6,
  val lastDetectedClipHash: String = "",
  val storageSoftQuotaMb: Int = 50,
  val storageWarnThreshold1: Int = 70,
  val storageWarnThreshold2: Int = 85,
  val storageWarnThreshold3: Int = 95,
)

@Singleton
class AppPreferencesRepository
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val cryptoManager: KeystoreCryptoManager,
  private val json: Json,
) {
  private object Keys {
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val APP_LANGUAGE = stringPreferencesKey("app_language")
    val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
    val AUTO_CAPTURE = booleanPreferencesKey("auto_capture_foreground")
    val INSTANT_AUTO_SAVE = booleanPreferencesKey("instant_auto_save")
    val AUTO_DETECT_SENSITIVE = booleanPreferencesKey("auto_detect_sensitive")
    val BLOCK_SENSITIVE_CLOUD = booleanPreferencesKey("block_sensitive_cloud")
    val SKIP_AUTO_SAVE_SENSITIVE = booleanPreferencesKey("skip_auto_save_sensitive")
    val EXCLUDED_SOURCE_APPS = stringPreferencesKey("excluded_source_apps_csv")
    val DELETE_CLOUD_ON_LOCAL_DELETE = booleanPreferencesKey("delete_cloud_on_local_delete")
    val RETENTION_DAYS = intPreferencesKey("history_retention_days")
    val AUTO_SYNC_ENABLED = booleanPreferencesKey("auto_sync_enabled")
    val AUTO_SYNC_INTERVAL_HOURS = intPreferencesKey("auto_sync_interval_hours")
    val LAST_DETECTED_HASH = stringPreferencesKey("last_detected_clip_hash")
    val LAST_GLOBAL_SYNC_AT = longPreferencesKey("last_global_sync_at")
    val STORAGE_SOFT_QUOTA_MB = intPreferencesKey("storage_soft_quota_mb")
    val STORAGE_WARN_1 = intPreferencesKey("storage_warn_1")
    val STORAGE_WARN_2 = intPreferencesKey("storage_warn_2")
    val STORAGE_WARN_3 = intPreferencesKey("storage_warn_3")

    val TELEGRAM_CONFIG_ENC = stringPreferencesKey("telegram_config_enc")
    val DISCORD_CONFIG_ENC = stringPreferencesKey("discord_config_enc")
    val DRIVE_CONFIG_ENC = stringPreferencesKey("drive_config_enc")
  }

  val userSettings: Flow<UserSettings> =
    context.smartClipboardDataStore.data
      .map { prefs ->
        UserSettings(
          themeMode =
            runCatching { ThemeMode.valueOf(prefs[Keys.THEME_MODE] ?: ThemeMode.SYSTEM.name) }
              .getOrDefault(ThemeMode.SYSTEM),
          language =
            runCatching { AppLanguage.valueOf(prefs[Keys.APP_LANGUAGE] ?: AppLanguage.ARABIC.name) }
              .getOrDefault(AppLanguage.ARABIC),
          appLockEnabled = prefs[Keys.APP_LOCK_ENABLED] ?: false,
          autoCaptureOnForeground = prefs[Keys.AUTO_CAPTURE] ?: true,
          instantAutoSave = prefs[Keys.INSTANT_AUTO_SAVE] ?: true,
          autoDetectSensitivePatterns = prefs[Keys.AUTO_DETECT_SENSITIVE] ?: true,
          blockSensitiveCloudSync = prefs[Keys.BLOCK_SENSITIVE_CLOUD] ?: true,
          skipAutoSaveSensitive = prefs[Keys.SKIP_AUTO_SAVE_SENSITIVE] ?: false,
          excludedSourceAppsCsv = prefs[Keys.EXCLUDED_SOURCE_APPS] ?: "",
          deleteCloudOnLocalDelete = prefs[Keys.DELETE_CLOUD_ON_LOCAL_DELETE] ?: false,
          historyRetentionDays = prefs[Keys.RETENTION_DAYS] ?: 30,
          autoSyncEnabled = prefs[Keys.AUTO_SYNC_ENABLED] ?: false,
          autoSyncIntervalHours = prefs[Keys.AUTO_SYNC_INTERVAL_HOURS] ?: 6,
          lastDetectedClipHash = prefs[Keys.LAST_DETECTED_HASH] ?: "",
          storageSoftQuotaMb = prefs[Keys.STORAGE_SOFT_QUOTA_MB] ?: 50,
          storageWarnThreshold1 = prefs[Keys.STORAGE_WARN_1] ?: 70,
          storageWarnThreshold2 = prefs[Keys.STORAGE_WARN_2] ?: 85,
          storageWarnThreshold3 = prefs[Keys.STORAGE_WARN_3] ?: 95,
        )
      }
      .distinctUntilChanged()
      .flowOn(Dispatchers.Default)

  val lastGlobalSyncAt: Flow<Long?> =
    context.smartClipboardDataStore.data
      .map { it[Keys.LAST_GLOBAL_SYNC_AT] }
      .distinctUntilChanged()

  val telegramConfig: Flow<TelegramConfig> =
    context.smartClipboardDataStore.data
      .map { it[Keys.TELEGRAM_CONFIG_ENC] }
      .distinctUntilChanged()
      .map { decodeEncrypted(it, TelegramConfig()) }
      .flowOn(Dispatchers.Default)

  val discordConfig: Flow<DiscordConfig> =
    context.smartClipboardDataStore.data
      .map { it[Keys.DISCORD_CONFIG_ENC] }
      .distinctUntilChanged()
      .map { decodeEncrypted(it, DiscordConfig()) }
      .flowOn(Dispatchers.Default)

  val googleDriveConfig: Flow<GoogleDriveConfig> =
    context.smartClipboardDataStore.data
      .map { it[Keys.DRIVE_CONFIG_ENC] }
      .distinctUntilChanged()
      .map { decodeEncrypted(it, GoogleDriveConfig()) }
      .flowOn(Dispatchers.Default)

  suspend fun updateThemeMode(mode: ThemeMode) {
    context.smartClipboardDataStore.edit { it[Keys.THEME_MODE] = mode.name }
  }

  suspend fun updateLanguage(language: AppLanguage) {
    context.smartClipboardDataStore.edit { it[Keys.APP_LANGUAGE] = language.name }
  }

  suspend fun updateAppLockEnabled(enabled: Boolean) {
    context.smartClipboardDataStore.edit { it[Keys.APP_LOCK_ENABLED] = enabled }
  }

  suspend fun updateAutoCapture(enabled: Boolean) {
    context.smartClipboardDataStore.edit { it[Keys.AUTO_CAPTURE] = enabled }
  }

  suspend fun updateInstantAutoSave(enabled: Boolean) {
    context.smartClipboardDataStore.edit { it[Keys.INSTANT_AUTO_SAVE] = enabled }
  }

  suspend fun updateSensitivePolicy(
    autoDetectPatterns: Boolean,
    blockCloudSync: Boolean,
    skipAutoSave: Boolean,
  ) {
    context.smartClipboardDataStore.edit {
      it[Keys.AUTO_DETECT_SENSITIVE] = autoDetectPatterns
      it[Keys.BLOCK_SENSITIVE_CLOUD] = blockCloudSync
      it[Keys.SKIP_AUTO_SAVE_SENSITIVE] = skipAutoSave
    }
  }

  suspend fun updateAutoDetectSensitivePatterns(enabled: Boolean) {
    context.smartClipboardDataStore.edit { it[Keys.AUTO_DETECT_SENSITIVE] = enabled }
  }

  suspend fun updateBlockSensitiveCloudSync(enabled: Boolean) {
    context.smartClipboardDataStore.edit { it[Keys.BLOCK_SENSITIVE_CLOUD] = enabled }
  }

  suspend fun updateSkipAutoSaveSensitive(enabled: Boolean) {
    context.smartClipboardDataStore.edit { it[Keys.SKIP_AUTO_SAVE_SENSITIVE] = enabled }
  }

  suspend fun updateExcludedAppsCsv(csv: String) {
    context.smartClipboardDataStore.edit { it[Keys.EXCLUDED_SOURCE_APPS] = csv.trim() }
  }

  suspend fun updateExcludedSourceAppsCsv(csv: String) = updateExcludedAppsCsv(csv)

  suspend fun updateDeleteCloudOnLocalDelete(enabled: Boolean) {
    context.smartClipboardDataStore.edit { it[Keys.DELETE_CLOUD_ON_LOCAL_DELETE] = enabled }
  }

  suspend fun updateStorageSoftQuotaMb(softQuotaMb: Int) {
    context.smartClipboardDataStore.edit {
      it[Keys.STORAGE_SOFT_QUOTA_MB] = softQuotaMb.coerceIn(5, 2048)
    }
  }

  suspend fun updateStorageWarnThresholds(warn1: Int, warn2: Int, warn3: Int) {
    val w1 = warn1.coerceIn(40, 90)
    val w2 = warn2.coerceIn(w1 + 5, 95)
    val w3 = warn3.coerceIn(w2 + 3, 99)
    context.smartClipboardDataStore.edit {
      it[Keys.STORAGE_WARN_1] = w1
      it[Keys.STORAGE_WARN_2] = w2
      it[Keys.STORAGE_WARN_3] = w3
    }
  }

  suspend fun updateStorageThresholds(
    softQuotaMb: Int,
    warn1: Int = 70,
    warn2: Int = 85,
    warn3: Int = 95,
  ) {
    val w1 = warn1.coerceIn(40, 90)
    val w2 = warn2.coerceIn(w1 + 5, 95)
    val w3 = warn3.coerceIn(w2 + 3, 99)
    context.smartClipboardDataStore.edit {
      it[Keys.STORAGE_SOFT_QUOTA_MB] = softQuotaMb.coerceIn(5, 2048)
      it[Keys.STORAGE_WARN_1] = w1
      it[Keys.STORAGE_WARN_2] = w2
      it[Keys.STORAGE_WARN_3] = w3
    }
  }

  suspend fun updateRetentionDays(days: Int) {
    context.smartClipboardDataStore.edit { it[Keys.RETENTION_DAYS] = days }
  }

  suspend fun updateAutoSync(enabled: Boolean, intervalHours: Int = 6) {
    context.smartClipboardDataStore.edit {
      it[Keys.AUTO_SYNC_ENABLED] = enabled
      it[Keys.AUTO_SYNC_INTERVAL_HOURS] = intervalHours
    }
  }

  suspend fun updateLastDetectedClipHash(hash: String) {
    context.smartClipboardDataStore.edit { it[Keys.LAST_DETECTED_HASH] = hash }
  }

  suspend fun updateLastGlobalSyncAt(timestamp: Long) {
    context.smartClipboardDataStore.edit { it[Keys.LAST_GLOBAL_SYNC_AT] = timestamp }
  }

  suspend fun saveTelegramConfig(config: TelegramConfig) {
    val encrypted = cryptoManager.encryptLocal(json.encodeToString(config))
    context.smartClipboardDataStore.edit { it[Keys.TELEGRAM_CONFIG_ENC] = encrypted }
  }

  suspend fun saveDiscordConfig(config: DiscordConfig) {
    val encrypted = cryptoManager.encryptLocal(json.encodeToString(config))
    context.smartClipboardDataStore.edit { it[Keys.DISCORD_CONFIG_ENC] = encrypted }
  }

  suspend fun saveGoogleDriveConfig(config: GoogleDriveConfig) {
    val encrypted = cryptoManager.encryptLocal(json.encodeToString(config))
    context.smartClipboardDataStore.edit { it[Keys.DRIVE_CONFIG_ENC] = encrypted }
  }

  suspend fun getTelegramConfigOnce(): TelegramConfig = telegramConfig.first()

  suspend fun getDiscordConfigOnce(): DiscordConfig = discordConfig.first()

  suspend fun getGoogleDriveConfigOnce(): GoogleDriveConfig = googleDriveConfig.first()

  suspend fun clearAllPreferences() {
    context.smartClipboardDataStore.edit { it.clear() }
  }

  private inline fun <reified T> decodeEncrypted(stored: String?, default: T): T {
    if (stored.isNullOrBlank()) return default
    return runCatching {
        val decrypted = cryptoManager.decryptLocal(stored)
        if (decrypted.isBlank()) default else json.decodeFromString<T>(decrypted)
      }
      .getOrDefault(default)
  }
}
