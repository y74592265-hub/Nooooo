package com.example.integrations.gdrive

import com.example.core.preferences.AppPreferencesRepository
import com.example.domain.model.ClipItem
import com.example.domain.model.ClipSource
import com.example.domain.model.SyncProviderId
import com.example.domain.usecase.ExportImportUseCase
import com.example.domain.usecase.RestoreSummary
import com.example.integrations.IntegrationProvider
import com.example.integrations.ProviderResult
import com.example.integrations.PulledClip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Response
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

  companion object {
    const val DEFAULT_FOLDER_NAME = "Smart Clipboard Backup"
    private const val RESUMABLE_THRESHOLD_BYTES = 256 * 1024
  }

  override val providerId: SyncProviderId = SyncProviderId.GOOGLE_DRIVE

  override suspend fun isConfigured(): Boolean {
    val cfg = prefs.getGoogleDriveConfigOnce()
    return cfg.enabled && (cfg.accountEmail.isNotBlank() || authHelper.getCachedAccessToken() != null)
  }

  private suspend fun obtainToken(includeVisibleScope: Boolean): String? {
    authHelper.getCachedAccessToken()?.let { return it }
    return when (val res = authHelper.requestAuthorization(includeVisibleScope)) {
      is DriveAuthState.Authorized -> res.accessToken
      else -> null
    }
  }

  private suspend fun <T> withTokenRefresh(
    includeVisibleScope: Boolean,
    block: suspend (token: String) -> Response<T>,
  ): Pair<Response<T>?, String?> {
    val initial = obtainToken(includeVisibleScope) ?: return null to null
    val firstResp = block(initial)
    if (firstResp.code() != 401) return firstResp to initial
    authHelper.clearCachedToken()
    val refreshed = obtainToken(includeVisibleScope) ?: return firstResp to null
    return block(refreshed) to refreshed
  }

  private suspend fun ensureVisibleBackupFolder(token: String): Pair<String?, String> {
    val cfg = prefs.getGoogleDriveConfigOnce()
    if (cfg.useAppDataFolder) return "appDataFolder" to "App Data Folder"
    if (!cfg.selectedFolderId.isNullOrBlank()) return cfg.selectedFolderId to cfg.selectedFolderName

    val targetName = cfg.selectedFolderName.ifBlank { DEFAULT_FOLDER_NAME }
    val escaped = targetName.replace("'", "\\'")
    val findResp =
      api.listFiles(
        bearerToken = "Bearer $token",
        spaces = "drive",
        query = "mimeType = 'application/vnd.google-apps.folder' and name = '$escaped' and trashed = false",
        pageSize = 5,
      )
    val existing = if (findResp.isSuccessful) findResp.body()?.files?.firstOrNull() else null
    if (existing != null) {
      prefs.saveGoogleDriveConfig(
        cfg.copy(selectedFolderId = existing.id, selectedFolderName = existing.name)
      )
      return existing.id to existing.name
    }

    val createResp = api.createFolder("Bearer $token", DriveCreateFolderRequest(name = targetName))
    if (createResp.isSuccessful && createResp.body() != null) {
      val created = createResp.body()!!
      prefs.saveGoogleDriveConfig(
        cfg.copy(selectedFolderId = created.id, selectedFolderName = created.name)
      )
      return created.id to created.name
    }
    return null to targetName
  }

  override suspend fun testConnection(): ProviderResult<String> =
    withContext(Dispatchers.IO) {
      val cfg = prefs.getGoogleDriveConfigOnce()
      val includeVisible = !cfg.useAppDataFolder
      try {
        val (listResp, validToken) =
          withTokenRefresh(includeVisible) { tok ->
            val space = if (cfg.useAppDataFolder) "appDataFolder" else "drive"
            api.listFiles("Bearer $tok", spaces = space, pageSize = 5)
          }
        if (validToken == null || listResp == null) {
          return@withContext ProviderResult.Error(
            "Google Drive sign-in consent required. Tap 'Connect Google Drive' first."
          )
        }
        if (!listResp.isSuccessful) {
          if (listResp.code() == 401) authHelper.clearCachedToken()
          return@withContext toDriveError(listResp, "Drive API error")
        }
        val infoResp = api.getUserInfo("Bearer $validToken")
        val email = infoResp.body()?.email ?: cfg.accountEmail
        if (includeVisible) {
          ensureVisibleBackupFolder(validToken)
        }
        val refreshedCfg = prefs.getGoogleDriveConfigOnce()
        prefs.saveGoogleDriveConfig(
          refreshedCfg.copy(
            enabled = true,
            accountEmail = email,
            lastStatus = "Connected ($email) • ${refreshedCfg.selectedFolderName}",
          )
        )
        ProviderResult.Success("Connected to Google Drive ($email)")
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: IOException) {
        ProviderResult.Error("Network error reaching Google Drive", isRetryable = true)
      } catch (e: Exception) {
        ProviderResult.Error("Drive check failed: ${e.localizedMessage ?: "Unexpected error"}")
      }
    }

  override suspend fun pushClips(clips: List<ClipItem>): ProviderResult<Int> =
    withContext(Dispatchers.IO) {
      val cfg = prefs.getGoogleDriveConfigOnce()
      if (!cfg.enabled) return@withContext ProviderResult.Error("Google Drive sync is disabled.")
      val result =
        backupNow(
          passphrase = null,
          incrementalIfPossible = cfg.autoIncrementalBackup && !cfg.lastBackupFileId.isNullOrBlank(),
        )
      when (result) {
        is ProviderResult.Success -> ProviderResult.Success(clips.size, result.detail)
        is ProviderResult.Error -> result
      }
    }

  override suspend fun pushSingleClipAndReturnRemoteId(clip: ClipItem): ProviderResult<String> =
    withContext(Dispatchers.IO) {
      val cfg = prefs.getGoogleDriveConfigOnce()
      if (!cfg.enabled) return@withContext ProviderResult.Error("Google Drive sync is disabled.")
      when (
        val res =
          backupNow(
            passphrase = null,
            incrementalIfPossible = cfg.autoIncrementalBackup && !cfg.lastBackupFileId.isNullOrBlank(),
          )
      ) {
        is ProviderResult.Success ->
          ProviderResult.Success("gdrive:${res.data.id}:${clip.contentHash.take(8)}", res.detail)
        is ProviderResult.Error -> res
      }
    }

  override suspend fun pullClips(): ProviderResult<List<PulledClip>> =
    // Auto background sync uses full/incremental backup snapshots via Cloud Recovery Center
    ProviderResult.Success(emptyList())

  override suspend fun fetchRecoveryItems(maxItems: Int): ProviderResult<List<PulledClip>> =
    withContext(Dispatchers.IO) {
      val cfg = prefs.getGoogleDriveConfigOnce()
      if (!cfg.enabled) {
        return@withContext ProviderResult.Error("Google Drive is not connected.")
      }
      when (val listRes = listBackups()) {
        is ProviderResult.Error -> listRes
        is ProviderResult.Success -> {
          val jsonBackups =
            listRes.data.filter { it.name.endsWith(".json", ignoreCase = true) }.take(5)
          if (jsonBackups.isEmpty()) {
            return@withContext ProviderResult.Success(
              emptyList(),
              detail =
                if (listRes.data.any { it.name.endsWith(".scbak", true) }) {
                  "Encrypted .scbak backups require manual passphrase restore in Backup & Sync"
                } else {
                  "No Drive backup snapshots found"
                },
            )
          }
          val seenHashes = HashSet<String>()
          val collected = mutableListOf<PulledClip>()
          for (backupFile in jsonBackups) {
            if (collected.size >= maxItems) break
            val (dlResp, _) =
              withTokenRefresh(!cfg.useAppDataFolder) { tok ->
                api.downloadFileContent("Bearer $tok", backupFile.id)
              }
            if (dlResp == null || !dlResp.isSuccessful || dlResp.body() == null) continue
            val raw = dlResp.body()!!.string()
            val parsed = runCatching { exportImportUseCase.parseAndVerifyBackup(raw, null) }.getOrNull()
              ?: continue
            for (clipDto in parsed.first.clips) {
              if (collected.size >= maxItems) break
              if (!seenHashes.add(clipDto.contentHash)) continue
              collected.add(
                PulledClip(
                  content = clipDto.content,
                  title = clipDto.title,
                  categoryHint = clipDto.categoryName,
                  tags = clipDto.tags,
                  source = ClipSource.GOOGLE_DRIVE,
                  remoteId = "gdrive:${backupFile.id}:${clipDto.contentHash.take(12)}",
                  remoteTimestamp = clipDto.updatedAt,
                )
              )
            }
          }
          ProviderResult.Success(collected)
        }
      }
    }

  suspend fun fetchDriveFolders(): ProviderResult<List<DriveFileDto>> =
    withContext(Dispatchers.IO) {
      try {
        val (resp, tok) =
          withTokenRefresh(includeVisibleScope = true) { token ->
            api.listFiles(
              bearerToken = "Bearer $token",
              spaces = "drive",
              query = "mimeType = 'application/vnd.google-apps.folder' and trashed = false",
              pageSize = 25,
            )
          }
        if (tok == null || resp == null) {
          return@withContext ProviderResult.Error("Drive folder authorization required.")
        }
        if (!resp.isSuccessful) {
          return@withContext toDriveError(resp, "Could not list Drive folders")
        }
        ProviderResult.Success(resp.body()?.files.orEmpty())
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: IOException) {
        ProviderResult.Error("Network error listing folders", isRetryable = true)
      } catch (e: Exception) {
        ProviderResult.Error("Could not list Drive folders: ${e.localizedMessage ?: "Unexpected error"}")
      }
    }

  suspend fun createBackupFolder(folderName: String = DEFAULT_FOLDER_NAME): ProviderResult<DriveFileDto> =
    withContext(Dispatchers.IO) {
      try {
        val (resp, tok) =
          withTokenRefresh(includeVisibleScope = true) { token ->
            api.createFolder("Bearer $token", DriveCreateFolderRequest(name = folderName))
          }
        if (tok == null || resp == null) {
          return@withContext ProviderResult.Error("Drive folder authorization required.")
        }
        if (!resp.isSuccessful || resp.body() == null) {
          return@withContext toDriveError(resp, "Failed to create Drive folder")
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
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: IOException) {
        ProviderResult.Error("Network error creating folder", isRetryable = true)
      } catch (e: Exception) {
        ProviderResult.Error("Failed to create Drive folder: ${e.localizedMessage ?: "Unexpected error"}")
      }
    }

  suspend fun listBackups(): ProviderResult<List<DriveFileDto>> =
    withContext(Dispatchers.IO) {
      val cfg = prefs.getGoogleDriveConfigOnce()
      val includeVisible = !cfg.useAppDataFolder
      try {
        val (resp, tok) =
          withTokenRefresh(includeVisible) { token ->
            val space = if (cfg.useAppDataFolder) "appDataFolder" else "drive"
            val query =
              if (cfg.useAppDataFolder) {
                "trashed = false"
              } else if (!cfg.selectedFolderId.isNullOrBlank()) {
                "'${cfg.selectedFolderId}' in parents and trashed = false"
              } else {
                "name contains 'smart_clipboard_' and trashed = false"
              }
            api.listFiles("Bearer $token", spaces = space, query = query, pageSize = 25)
          }
        if (tok == null || resp == null) {
          return@withContext ProviderResult.Error("Google Drive authorization required.")
        }
        if (!resp.isSuccessful) {
          if (resp.code() == 401) authHelper.clearCachedToken()
          return@withContext toDriveError(resp, "Failed to list backups")
        }
        ProviderResult.Success(resp.body()?.files.orEmpty())
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: IOException) {
        ProviderResult.Error("Network error listing backups", isRetryable = true)
      } catch (e: Exception) {
        ProviderResult.Error("Failed to list backups: ${e.localizedMessage ?: "Unexpected error"}")
      }
    }

  suspend fun backupNow(
    passphrase: String?,
    incrementalIfPossible: Boolean = false,
  ): ProviderResult<DriveFileDto> =
    withContext(Dispatchers.IO) {
      val initialCfg = prefs.getGoogleDriveConfigOnce()
      val includeVisible = !initialCfg.useAppDataFolder
      val token = obtainToken(includeVisible)
        ?: return@withContext ProviderResult.Error("Google Drive sign-in required.")

      try {
        val (folderId, folderDisplay) =
          if (!initialCfg.useAppDataFolder) {
            ensureVisibleBackupFolder(token)
          } else {
            "appDataFolder" to "App Data Folder"
          }
        val cfg = prefs.getGoogleDriveConfigOnce()
        val isIncremental =
          incrementalIfPossible &&
            cfg.lastBackupAt != null &&
            cfg.lastBackupAt > 0L &&
            !cfg.lastBackupFileId.isNullOrBlank()
        val useEncrypted = cfg.encryptBackups && !passphrase.isNullOrBlank()

        val rawContent =
          if (useEncrypted) {
            exportImportUseCase.exportEncryptedBackup(
              passphrase = passphrase!!,
              isIncremental = isIncremental,
              sinceTimestamp = if (isIncremental) cfg.lastBackupAt else null,
              baseBackupFileId = if (isIncremental) cfg.lastBackupFileId else null,
            )
          } else if (isIncremental) {
            exportImportUseCase.exportIncrementalAsJson(
              sinceTimestamp = cfg.lastBackupAt!!,
              baseBackupFileId = cfg.lastBackupFileId,
            )
          } else {
            exportImportUseCase.exportAsJson()
          }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val prefix = if (isIncremental) "smart_clipboard_delta_" else "smart_clipboard_backup_"
        val ext = if (useEncrypted) "scbak" else "json"
        val fileName = "$prefix$stamp.$ext"

        val metadataJson =
          JSONObject().apply {
            put("name", fileName)
            if (cfg.useAppDataFolder) {
              put("parents", JSONArray(listOf("appDataFolder")))
            } else if (!folderId.isNullOrBlank()) {
              put("parents", JSONArray(listOf(folderId)))
            }
          }

        val contentBytes = rawContent.toByteArray(Charsets.UTF_8)
        val (uploadResp, _) =
          withTokenRefresh(includeVisible) { tok ->
            if (contentBytes.size > RESUMABLE_THRESHOLD_BYTES) {
              val metaReq =
                metadataJson
                  .toString()
                  .toRequestBody("application/json; charset=utf-8".toMediaType())
              val initResp =
                api.startResumableUpload(
                  bearerToken = "Bearer $tok",
                  contentLength = contentBytes.size.toLong(),
                  metadata = metaReq,
                )
              val sessionUri = initResp.headers()["Location"]
              if (initResp.isSuccessful && !sessionUri.isNullOrBlank()) {
                val contentBody =
                  rawContent.toRequestBody("application/json; charset=utf-8".toMediaType())
                return@withTokenRefresh api.uploadResumableContent(sessionUri, contentBody)
              }
            }
            val metadataPart =
              metadataJson
                .toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val contentBody =
              rawContent.toRequestBody("application/json; charset=utf-8".toMediaType())
            val filePart = MultipartBody.Part.createFormData("file", fileName, contentBody)
            api.uploadBackupFile("Bearer $tok", metadataPart, filePart)
          }

        if (uploadResp == null || !uploadResp.isSuccessful || uploadResp.body() == null) {
          val code = uploadResp?.code() ?: 401
          if (code == 401) authHelper.clearCachedToken()
          return@withContext if (uploadResp != null) {
            toDriveError(uploadResp, "Drive upload failed")
          } else {
            ProviderResult.Error("Drive upload failed (HTTP $code)", isRetryable = true)
          }
        }
        val uploaded = uploadResp.body()!!
        prefs.saveGoogleDriveConfig(
          cfg.copy(
            lastBackupAt = System.currentTimeMillis(),
            lastBackupFileId = uploaded.id,
            lastStatus = "Backed up $fileName ($folderDisplay)",
          )
        )
        ProviderResult.Success(uploaded, detail = fileName)
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: IOException) {
        ProviderResult.Error("Network error uploading backup", isRetryable = true)
      } catch (e: Exception) {
        ProviderResult.Error("Backup failed: ${e.localizedMessage ?: "Unexpected error"}")
      }
    }

  suspend fun restoreBackup(fileId: String, passphrase: String?): ProviderResult<RestoreSummary> =
    withContext(Dispatchers.IO) {
      val cfg = prefs.getGoogleDriveConfigOnce()
      try {
        val (resp, tok) =
          withTokenRefresh(!cfg.useAppDataFolder) { token ->
            api.downloadFileContent("Bearer $token", fileId)
          }
        if (tok == null || resp == null) {
          return@withContext ProviderResult.Error("Google Drive sign-in required.")
        }
        if (!resp.isSuccessful || resp.body() == null) {
          return@withContext toDriveError(resp, "Failed to download backup")
        }
        val raw = resp.body()!!.string()
        val summary = exportImportUseCase.restoreFromRawContent(raw, passphrase)
        ProviderResult.Success(summary)
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: IOException) {
        ProviderResult.Error("Network error downloading backup", isRetryable = true)
      } catch (e: Exception) {
        ProviderResult.Error("Restore failed: ${e.localizedMessage ?: "Unexpected error"}")
      }
    }

  override suspend fun deleteRemoteClip(remoteId: String): ProviderResult<Unit> =
    withContext(Dispatchers.IO) {
      if (!remoteId.startsWith("gdrive_file:")) return@withContext ProviderResult.Success(Unit)
      val fileId = remoteId.removePrefix("gdrive_file:")
      val cfg = prefs.getGoogleDriveConfigOnce()
      try {
        val (resp, _) =
          withTokenRefresh(!cfg.useAppDataFolder) { tok -> api.deleteFile("Bearer $tok", fileId) }
        if (resp != null && (resp.isSuccessful || resp.code() == 404)) {
          ProviderResult.Success(Unit)
        } else if (resp != null) {
          toDriveError(resp, "Drive delete failed")
        } else {
          ProviderResult.Error("Drive authorization required")
        }
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: IOException) {
        ProviderResult.Error("Network error deleting Drive file", isRetryable = true)
      } catch (e: Exception) {
        ProviderResult.Error("Drive delete failed: ${e.localizedMessage ?: "Unexpected error"}")
      }
    }

  private fun <T> toDriveError(resp: Response<T>, prefix: String): ProviderResult.Error {
    val code = resp.code()
    val retryAfter = resp.headers()["Retry-After"]?.toLongOrNull()
    val retryable = code == 429 || code >= 500
    return ProviderResult.Error(
      message = "$prefix (HTTP $code)",
      isRetryable = retryable,
      retryAfterSeconds = retryAfter,
    )
  }
}
