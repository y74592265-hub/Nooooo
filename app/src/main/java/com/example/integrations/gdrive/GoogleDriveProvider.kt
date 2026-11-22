package com.example.integrations.gdrive

import com.example.core.preferences.AppPreferencesRepository
import com.example.domain.model.ClipItem
import com.example.domain.model.SyncProviderId
import com.example.domain.usecase.ExportImportUseCase
import com.example.domain.usecase.RestoreSummary
import com.example.integrations.IntegrationProvider
import com.example.integrations.ProviderResult
import com.example.integrations.PulledClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoogleDriveProvider
@Inject
constructor(
  private val api: GoogleDriveApiService,
  private val authHelper: GoogleDriveAuthHelper,
  private val prefs: AppPreferencesRepository,
  private val exportImportUseCase: ExportImportUseCase,
) : IntegrationProvider {

  override val providerId: SyncProviderId = SyncProviderId.GOOGLE_DRIVE

  override suspend fun isConfigured(): Boolean {
    val cfg = prefs.getGoogleDriveConfigOnce()
    return cfg.enabled && (cfg.accountEmail.isNotBlank() || authHelper.getCachedAccessToken() != null)
  }

  private suspend fun obtainSilentToken(includeVisibleScope: Boolean): String? {
    authHelper.getCachedAccessToken()?.let { return it }
    return when (val res = authHelper.requestAuthorization(includeVisibleScope)) {
      is DriveAuthState.Authorized -> res.accessToken
      else -> null
    }
  }

  override suspend fun testConnection(): ProviderResult<String> =
    withContext(Dispatchers.IO) {
      val cfg = prefs.getGoogleDriveConfigOnce()
      val token = obtainSilentToken(!cfg.useAppDataFolder)
        ?: return@withContext ProviderResult.Error(
          "Google Drive sign-in consent required. Tap 'Connect Google Drive' first."
        )
      try {
        val infoResp = api.getUserInfo("Bearer $token")
        val email = infoResp.body()?.email ?: cfg.accountEmail
        val space = if (cfg.useAppDataFolder) "appDataFolder" else "drive"
        val listResp = api.listFiles("Bearer $token", spaces = space, pageSize = 5)
        if (!listResp.isSuccessful) {
          if (listResp.code() == 401) authHelper.clearCachedToken()
          return@withContext ProviderResult.Error("Drive API error HTTP ${listResp.code()}")
        }
        prefs.saveGoogleDriveConfig(
          cfg.copy(enabled = true, accountEmail = email, lastStatus = "Connected ($email)")
        )
        ProviderResult.Success("Connected to Google Drive ($email)")
      } catch (e: IOException) {
        ProviderResult.Error("Network error reaching Google Drive", isRetryable = true)
      } catch (e: Exception) {
        ProviderResult.Error("Drive check failed: ${e.localizedMessage}")
      }
    }

  override suspend fun pushClips(clips: List<ClipItem>): ProviderResult<Int> =
    withContext(Dispatchers.IO) {
      val cfg = prefs.getGoogleDriveConfigOnce()
      if (!cfg.enabled) return@withContext ProviderResult.Error("Google Drive sync is disabled.")
      // Encrypted backup requires a passphrase via Backup & Sync screen; auto-sync uploads plain JSON to private appDataFolder
      val result = backupNow(passphrase = null)
      when (result) {
        is ProviderResult.Success -> ProviderResult.Success(clips.size, result.detail)
        is ProviderResult.Error -> result
      }
    }

  override suspend fun pullClips(): ProviderResult<List<PulledClip>> {
    // Google Drive operates via full snapshot Backup & Restore rather than message-stream polling
    return ProviderResult.Success(emptyList())
  }

  suspend fun fetchDriveFolders(): ProviderResult<List<DriveFileDto>> =
    withContext(Dispatchers.IO) {
      val token = obtainSilentToken(includeVisibleScope = true)
        ?: return@withContext ProviderResult.Error("Drive folder authorization required.")
      try {
        val resp =
          api.listFiles(
            bearerToken = "Bearer $token",
            spaces = "drive",
            query = "mimeType = 'application/vnd.google-apps.folder' and trashed = false",
            pageSize = 25,
          )
        if (!resp.isSuccessful) {
          return@withContext ProviderResult.Error("Could not list Drive folders (HTTP ${resp.code()})")
        }
        ProviderResult.Success(resp.body()?.files.orEmpty())
      } catch (e: IOException) {
        ProviderResult.Error("Network error listing folders", isRetryable = true)
      }
    }

  suspend fun createBackupFolder(folderName: String = "Smart Clipboard Backups"): ProviderResult<DriveFileDto> =
    withContext(Dispatchers.IO) {
      val token = obtainSilentToken(includeVisibleScope = true)
        ?: return@withContext ProviderResult.Error("Drive folder authorization required.")
      try {
        val resp = api.createFolder("Bearer $token", DriveCreateFolderRequest(name = folderName))
        if (!resp.isSuccessful || resp.body() == null) {
          return@withContext ProviderResult.Error("Failed to create Drive folder (HTTP ${resp.code()})")
        }
        val folder = resp.body()!!
        val cfg = prefs.getGoogleDriveConfigOnce()
        prefs.saveGoogleDriveConfig(
          cfg.copy(
            useAppDataFolder = false,
            selectedFolderId = folder.id,
            selectedFolderName = folder.name,
          )
        )
        ProviderResult.Success(folder)
      } catch (e: IOException) {
        ProviderResult.Error("Network error creating folder", isRetryable = true)
      }
    }

  suspend fun listBackups(): ProviderResult<List<DriveFileDto>> =
    withContext(Dispatchers.IO) {
      val cfg = prefs.getGoogleDriveConfigOnce()
      val token = obtainSilentToken(!cfg.useAppDataFolder)
        ?: return@withContext ProviderResult.Error("Google Drive authorization required.")
      try {
        val space = if (cfg.useAppDataFolder) "appDataFolder" else "drive"
        val query =
          if (cfg.useAppDataFolder) {
            "trashed = false"
          } else if (!cfg.selectedFolderId.isNullOrBlank()) {
            "'${cfg.selectedFolderId}' in parents and trashed = false"
          } else {
            "name contains 'smart_clipboard_backup' and trashed = false"
          }
        val resp = api.listFiles("Bearer $token", spaces = space, query = query, pageSize = 20)
        if (!resp.isSuccessful) {
          if (resp.code() == 401) authHelper.clearCachedToken()
          return@withContext ProviderResult.Error("Failed to list backups (HTTP ${resp.code()})")
        }
        ProviderResult.Success(resp.body()?.files.orEmpty())
      } catch (e: IOException) {
        ProviderResult.Error("Network error listing backups", isRetryable = true)
      }
    }

  suspend fun backupNow(passphrase: String?): ProviderResult<DriveFileDto> =
    withContext(Dispatchers.IO) {
      val cfg = prefs.getGoogleDriveConfigOnce()
      val token = obtainSilentToken(!cfg.useAppDataFolder)
        ?: return@withContext ProviderResult.Error("Google Drive sign-in required.")

      try {
        val useEncrypted = cfg.encryptBackups && !passphrase.isNullOrBlank()
        val rawContent =
          if (useEncrypted) {
            exportImportUseCase.exportEncryptedBackup(passphrase!!)
          } else {
            exportImportUseCase.exportAsJson()
          }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val ext = if (useEncrypted) "scbak" else "json"
        val fileName = "smart_clipboard_backup_$stamp.$ext"

        val metadataJson =
          JSONObject().apply {
            put("name", fileName)
            if (cfg.useAppDataFolder) {
              put("parents", JSONArray(listOf("appDataFolder")))
            } else if (!cfg.selectedFolderId.isNullOrBlank()) {
              put("parents", JSONArray(listOf(cfg.selectedFolderId)))
            }
          }

        val metadataPart =
          metadataJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val contentBody = rawContent.toRequestBody("application/json; charset=utf-8".toMediaType())
        val filePart = MultipartBody.Part.createFormData("file", fileName, contentBody)

        val resp = api.uploadBackupFile("Bearer $token", metadataPart, filePart)
        if (!resp.isSuccessful || resp.body() == null) {
          val retryable = resp.code() == 429 || resp.code() >= 500
          if (resp.code() == 401) authHelper.clearCachedToken()
          return@withContext ProviderResult.Error(
            "Drive upload failed (HTTP ${resp.code()})",
            isRetryable = retryable,
          )
        }
        val uploaded = resp.body()!!
        prefs.saveGoogleDriveConfig(
          cfg.copy(
            lastBackupAt = System.currentTimeMillis(),
            lastBackupFileId = uploaded.id,
            lastStatus = "Backed up $fileName",
          )
        )
        ProviderResult.Success(uploaded, detail = fileName)
      } catch (e: IOException) {
        ProviderResult.Error("Network error uploading backup", isRetryable = true)
      } catch (e: Exception) {
        ProviderResult.Error("Backup failed: ${e.localizedMessage}")
      }
    }

  suspend fun restoreBackup(fileId: String, passphrase: String?): ProviderResult<RestoreSummary> =
    withContext(Dispatchers.IO) {
      val cfg = prefs.getGoogleDriveConfigOnce()
      val token = obtainSilentToken(!cfg.useAppDataFolder)
        ?: return@withContext ProviderResult.Error("Google Drive sign-in required.")
      try {
        val resp = api.downloadFileContent("Bearer $token", fileId)
        if (!resp.isSuccessful || resp.body() == null) {
          return@withContext ProviderResult.Error("Failed to download backup (HTTP ${resp.code()})")
        }
        val raw = resp.body()!!.string()
        val summary = exportImportUseCase.restoreFromRawContent(raw, passphrase)
        ProviderResult.Success(summary)
      } catch (e: IOException) {
        ProviderResult.Error("Network error downloading backup", isRetryable = true)
      } catch (e: Exception) {
        ProviderResult.Error("Restore failed: ${e.localizedMessage}")
      }
    }
}
