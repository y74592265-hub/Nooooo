package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.clipboard.SystemClipboardHelper
import com.example.core.preferences.AppPreferencesRepository
import com.example.core.preferences.UserSettings
import com.example.core.security.KeystoreCryptoManager
import com.example.data.local.SmartClipboardDatabase
import com.example.data.repository.CategoryRepositoryImpl
import com.example.data.repository.ClipRepositoryImpl
import com.example.data.repository.SyncLogRepositoryImpl
import com.example.data.repository.SyncQueueRepositoryImpl
import com.example.domain.model.ClipItem
import com.example.domain.model.ClipSource
import com.example.domain.model.ClipSyncState
import com.example.domain.model.CloudRecoveryItem
import com.example.domain.model.ConflictResolution
import com.example.domain.model.GoogleDriveConfig
import com.example.domain.model.ProviderHealthSummary
import com.example.domain.model.RecoveryMatchStatus
import com.example.domain.model.RecoveryScanResult
import com.example.domain.model.StorageCapacityStats
import com.example.domain.model.StorageThresholdLevel
import com.example.domain.model.SyncProviderId
import com.example.domain.usecase.CloudRecoveryUseCase
import com.example.domain.usecase.ExportImportUseCase
import com.example.domain.usecase.StorageCapacityUseCase
import com.example.integrations.IntegrationProvider
import com.example.integrations.ProviderResult
import com.example.integrations.PulledClip
import com.example.integrations.discord.DiscordApiService
import com.example.integrations.discord.DiscordAuthor
import com.example.integrations.discord.DiscordBotUser
import com.example.integrations.discord.DiscordChannel
import com.example.integrations.discord.DiscordCreateChannelRequest
import com.example.integrations.discord.DiscordGuild
import com.example.integrations.discord.DiscordMessageDto
import com.example.integrations.discord.DiscordMessageRequest
import com.example.integrations.discord.DiscordProvider
import com.example.integrations.discord.DiscordRole
import com.example.integrations.discord.DiscordWebhookPayload
import com.example.integrations.gdrive.DriveAuthState
import com.example.integrations.gdrive.DriveCreateFolderRequest
import com.example.integrations.gdrive.DriveFileDto
import com.example.integrations.gdrive.DriveFileListResponse
import com.example.integrations.gdrive.GoogleDriveApiService
import com.example.integrations.gdrive.GoogleDriveAuthHelper
import com.example.integrations.gdrive.GoogleDriveProvider
import com.example.integrations.gdrive.GoogleUserInfoResponse
import com.example.ui.screens.RecoveryCenterScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StorageCapacityScreen
import com.example.ui.theme.SmartClipboardTheme
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DiscordVaultDriveAndRecoveryTest {

  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var context: Context
  private lateinit var db: SmartClipboardDatabase
  private lateinit var crypto: KeystoreCryptoManager
  private lateinit var prefs: AppPreferencesRepository
  private lateinit var clipRepo: ClipRepositoryImpl
  private lateinit var categoryRepo: CategoryRepositoryImpl
  private lateinit var syncLogRepo: SyncLogRepositoryImpl
  private lateinit var syncQueueRepo: SyncQueueRepositoryImpl
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  @Before
  fun setUp() = runTest {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, SmartClipboardDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    crypto = KeystoreCryptoManager()
    prefs = AppPreferencesRepository(context, crypto, json)
    prefs.clearAllPreferences()
    clipRepo = ClipRepositoryImpl(db.clipDao(), crypto, SystemClipboardHelper(context))
    categoryRepo = CategoryRepositoryImpl(db.categoryDao())
    syncLogRepo = SyncLogRepositoryImpl(db.syncLogDao())
    syncQueueRepo = SyncQueueRepositoryImpl(db.syncQueueDao())
    categoryRepo.ensureDefaultCategories()
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `discord private vault rejects user tokens checks bitmasks creates permission overwrites and deduplicates`() =
    runTest {
      var capturedChannelRequest: DiscordCreateChannelRequest? = null
      val fakeDiscordApi =
        object : DiscordApiService {
          override suspend fun executeWebhook(webhookUrl: String, payload: DiscordWebhookPayload) =
            Response.success(Unit)

          override suspend fun getWebhookInfo(webhookUrl: String) =
            Response.success(DiscordChannel("ch_vault", "smart-clipboard-vault"))

          override suspend fun getCurrentBotUser(botAuthorization: String) =
            Response.success(DiscordBotUser("bot_900", "ClipboardVaultBot", bot = true))

          override suspend fun getBotGuilds(botAuthorization: String) =
            Response.success(
              listOf(
                DiscordGuild("guild_admin", "Vault Server", owner = false, permissions = "16"), // MANAGE_CHANNELS 0x10
                DiscordGuild("guild_readonly", "Public Server", owner = false, permissions = "1024"), // VIEW_CHANNEL only
              )
            )

          override suspend fun getGuildChannels(botAuthorization: String, guildId: String) =
            Response.success(emptyList<DiscordChannel>())

          override suspend fun getGuildRoles(botAuthorization: String, guildId: String) =
            Response.success(emptyList<DiscordRole>())

          override suspend fun createGuildChannel(
            botAuthorization: String,
            guildId: String,
            body: DiscordCreateChannelRequest,
          ): Response<DiscordChannel> {
            capturedChannelRequest = body
            return Response.success(DiscordChannel("ch_vault", body.name, type = 0, guildId = guildId))
          }

          override suspend fun getChannel(botAuthorization: String, channelId: String) =
            Response.success(DiscordChannel(channelId, "smart-clipboard-vault"))

          override suspend fun sendChannelMessage(
            botAuthorization: String,
            channelId: String,
            body: DiscordMessageRequest,
          ) = Response.success(DiscordMessageDto("dc_msg_777", body.content))

          override suspend fun getChannelMessages(
            botAuthorization: String,
            channelId: String,
            afterMessageId: String?,
            limit: Int,
            beforeMessageId: String?,
          ) =
            Response.success(
              listOf(
                DiscordMessageDto("100", "**[Work]**\nRecovered remote snippet", DiscordAuthor("user_1", "owner", bot = false)),
                DiscordMessageDto("101", "Bot echo", DiscordAuthor("bot_900", "ClipboardVaultBot", bot = true)),
              )
            )

          override suspend fun deleteChannelMessage(
            botAuthorization: String,
            channelId: String,
            messageId: String,
          ) = Response.success(Unit)
        }

      val provider = DiscordProvider(fakeDiscordApi, prefs, categoryRepo)

      // 1. User Tokens / Self-Bots strictly rejected
      assertNull(provider.sanitizeBotToken("mfa.UserTokenExample12345"))
      val rejected = provider.verifyBotAndFetchGuilds("mfa.UserTokenExample12345")
      assertTrue(rejected is ProviderResult.Error)

      // 2. Verify Bot and inspect Manage Channels bitmasks
      val verified = provider.verifyBotAndFetchGuilds("Bot OfficialBotToken123") as ProviderResult.Success
      assertEquals("ClipboardVaultBot", verified.data.botUsername)
      val adminGuild = verified.data.guilds.first { it.id == "guild_admin" }
      val readonlyGuild = verified.data.guilds.first { it.id == "guild_readonly" }
      assertTrue(adminGuild.canManageChannels)
      assertFalse(readonlyGuild.canManageChannels)

      // 3. Create Private Vault with permission_overwrites (@everyone deny VIEW_CHANNEL, Bot + User + Role allow)
      val created =
        provider.createOrLinkPrivateVaultChannel(
          rawBotToken = "OfficialBotToken123",
          guildId = "guild_admin",
          guildName = "Vault Server",
          channelName = "smart-clipboard-vault",
          allowedUserId = "user_55",
          allowedRoleId = "role_88",
        )
      assertTrue(created is ProviderResult.Success)
      assertNotNull(capturedChannelRequest)
      val overwrites = capturedChannelRequest!!.permissionOverwrites.orEmpty()
      val everyoneDeny = overwrites.firstOrNull { it.id == "guild_admin" && it.type == 0 }
      val botAllow = overwrites.firstOrNull { it.id == "bot_900" && it.type == 1 }
      val userAllow = overwrites.firstOrNull { it.id == "user_55" && it.type == 1 }
      val roleAllow = overwrites.firstOrNull { it.id == "role_88" && it.type == 0 }
      assertNotNull(everyoneDeny)
      assertEquals(DiscordProvider.PERMISSION_VIEW_CHANNEL.toString(), everyoneDeny!!.deny)
      assertNotNull(botAllow)
      assertEquals(DiscordProvider.VAULT_ALLOW_MASK.toString(), botAllow!!.allow)
      assertNotNull(userAllow)
      assertNotNull(roleAllow)

      // 4. Push clip captures real Discord message ID & recovery filters bot echoes on pull
      val clip = ClipItem(id = 1L, content = "Vault secret payload", contentHash = "h1")
      val pushId = provider.pushSingleClipAndReturnRemoteId(clip) as ProviderResult.Success
      assertEquals("discord:ch_vault:dc_msg_777", pushId.data)

      val pulled = provider.pullClips() as ProviderResult.Success
      assertEquals(1, pulled.data.size)
      assertEquals("Recovered remote snippet", pulled.data.first().content)
    }

  @Test
  fun `google drive creates Smart Clipboard Backup folder refreshes 401 token and supports incremental restore`() =
    runTest {
      var authCalls = 0
      var cached: String? = "expired_token"
      val fakeAuthHelper =
        object : GoogleDriveAuthHelper(context) {
          override fun getCachedAccessToken(): String? = cached

          override fun clearCachedToken() {
            cached = null
          }

          override suspend fun requestAuthorization(includeVisibleFolderScope: Boolean): DriveAuthState {
            authCalls++
            cached = "fresh_token"
            return DriveAuthState.Authorized("fresh_token")
          }
        }

      var listAttempt = 0
      val uploadedBodies = mutableListOf<Pair<String, String>>()
      val files = mutableListOf<DriveFileDto>()
      val fileContents = mutableMapOf<String, String>()

      val fakeDriveApi =
        object : GoogleDriveApiService {
          override suspend fun getUserInfo(bearerToken: String) =
            Response.success(GoogleUserInfoResponse("vault@example.com", "Vault User"))

          override suspend fun listFiles(
            bearerToken: String,
            spaces: String,
            query: String?,
            fields: String,
            orderBy: String,
            pageSize: Int,
          ): Response<DriveFileListResponse> {
            listAttempt++
            if (bearerToken == "Bearer expired_token") {
              return Response.error(401, "Expired".toResponseBody())
            }
            val matched =
              if (query?.contains("application/vnd.google-apps.folder") == true) {
                files.filter { it.mimeType == "application/vnd.google-apps.folder" }
              } else {
                files.filter { it.mimeType != "application/vnd.google-apps.folder" }
              }
            return Response.success(DriveFileListResponse(matched))
          }

          override suspend fun createFolder(
            bearerToken: String,
            body: DriveCreateFolderRequest,
          ): Response<DriveFileDto> {
            val dto = DriveFileDto(id = "folder_scb", name = body.name, mimeType = body.mimeType)
            files.add(dto)
            return Response.success(dto)
          }

          override suspend fun uploadBackupFile(
            bearerToken: String,
            metadata: RequestBody,
            file: MultipartBody.Part,
          ): Response<DriveFileDto> {
            val buf = Buffer()
            file.body.writeTo(buf)
            val raw = buf.readUtf8()
            val fileId = "backup_${uploadedBodies.size + 1}"
            val name =
              if (raw.contains("\"isIncremental\":true")) "smart_clipboard_delta_2.json"
              else "smart_clipboard_backup_1.json"
            val dto = DriveFileDto(id = fileId, name = name, size = raw.length.toString())
            files.add(dto)
            fileContents[fileId] = raw
            uploadedBodies.add(name to raw)
            return Response.success(dto)
          }

          override suspend fun startResumableUpload(
            bearerToken: String,
            contentType: String,
            contentLength: Long,
            metadata: RequestBody,
          ) = Response.error<Unit>(500, "unused".toResponseBody())

          override suspend fun uploadResumableContent(
            uploadSessionUrl: String,
            content: RequestBody,
          ) = Response.error<DriveFileDto>(500, "unused".toResponseBody())

          override suspend fun downloadFileContent(
            bearerToken: String,
            fileId: String,
          ): Response<ResponseBody> {
            val raw = fileContents[fileId] ?: return Response.error(404, "missing".toResponseBody())
            return Response.success(raw.toResponseBody("application/json".toMediaType()))
          }

          override suspend fun deleteFile(bearerToken: String, fileId: String) = Response.success(Unit)
        }

      prefs.saveGoogleDriveConfig(
        GoogleDriveConfig(enabled = true, useAppDataFolder = false, selectedFolderName = GoogleDriveProvider.DEFAULT_FOLDER_NAME)
      )
      val exportImportUseCase = ExportImportUseCase(clipRepo, categoryRepo, crypto, json)
      val driveProvider = GoogleDriveProvider(fakeDriveApi, fakeAuthHelper, prefs, exportImportUseCase)

      // 1. Test connection refreshes expired 401 token and auto-creates visible "Smart Clipboard Backup" folder
      val conn = driveProvider.testConnection()
      assertTrue(conn is ProviderResult.Success)
      assertEquals(1, authCalls)
      assertEquals("folder_scb", prefs.getGoogleDriveConfigOnce().selectedFolderId)

      // 2. Full backup, then add a new clip and verify incremental delta backup
      clipRepo.saveNewOrDeduplicate("", "Initial Drive Clip", updatedAtOverride = 1_000L)
      val fullRes = driveProvider.backupNow(passphrase = null, incrementalIfPossible = true)
      assertTrue(fullRes is ProviderResult.Success)

      val cfgAfterFull = prefs.getGoogleDriveConfigOnce()
      clipRepo.saveNewOrDeduplicate("", "Delta Incremental Clip", updatedAtOverride = (cfgAfterFull.lastBackupAt ?: 0L) + 10_000L)
      val deltaRes = driveProvider.backupNow(passphrase = null, incrementalIfPossible = true)
      assertTrue(deltaRes is ProviderResult.Success)
      assertTrue(uploadedBodies.last().first.startsWith("smart_clipboard_delta_"))

      // 3. Wipe local DB and restore missing items via fetchRecoveryItems + restoreBackup
      clipRepo.wipeAllData()
      assertEquals(0, clipRepo.getAllActiveClipsOnce().size)
      val recoveryItems = (driveProvider.fetchRecoveryItems(50) as ProviderResult.Success).data
      assertEquals(2, recoveryItems.size)
      val restoreRes = driveProvider.restoreBackup((fullRes as ProviderResult.Success).data.id, null) as ProviderResult.Success
      assertTrue(restoreRes.data.importedCount >= 1)
      assertTrue(restoreRes.data.checksumVerified)
    }

  @Test
  fun `cloud recovery diff handles new local newer conflict resolution and storage thresholds protect unsynced`() =
    runTest {
      val now = System.currentTimeMillis()
      val existingSynced = clipRepo.saveNewOrDeduplicate("", "Already synced snippet", remoteId = "tg:1:10")
      val localNewer = clipRepo.saveNewOrDeduplicate("", "Local edited newer", remoteId = "tg:1:20", updatedAtOverride = now + 20_000L)
      val conflictClip = clipRepo.saveNewOrDeduplicate("", "Old local version", remoteId = "tg:1:30", updatedAtOverride = now - 50_000L)

      val fakeProvider =
        object : IntegrationProvider {
          override val providerId = SyncProviderId.TELEGRAM

          override suspend fun isConfigured() = true

          override suspend fun testConnection() = ProviderResult.Success("OK")

          override suspend fun pushClips(clips: List<ClipItem>) = ProviderResult.Success(0)

          override suspend fun pullClips() = ProviderResult.Success(emptyList<PulledClip>())

          override suspend fun fetchRecoveryItems(maxItems: Int) =
            ProviderResult.Success(
              listOf(
                PulledClip("Already synced snippet", source = ClipSource.TELEGRAM, remoteId = "tg:1:10", remoteTimestamp = now),
                PulledClip("Remote older edit", source = ClipSource.TELEGRAM, remoteId = "tg:1:20", remoteTimestamp = now),
                PulledClip("Cloud newer conflict edit", source = ClipSource.TELEGRAM, remoteId = "tg:1:30", remoteTimestamp = now),
                PulledClip("Brand new cloud item", source = ClipSource.TELEGRAM, remoteId = "tg:1:40", remoteTimestamp = now),
              )
            )
        }

      val recoveryUseCase = CloudRecoveryUseCase(setOf(fakeProvider), clipRepo, categoryRepo, syncQueueRepo, syncLogRepo, crypto, prefs)
      val scan = recoveryUseCase.scanProviders()
      assertEquals(1, scan.alreadySyncedCount)
      assertEquals(1, scan.localNewerCount)
      assertEquals(1, scan.conflictCount)
      assertEquals(1, scan.newRemoteCount)

      val resolutions =
        mapOf(
          "tg:1:20" to ConflictResolution.KEEP_LOCAL,
          "tg:1:30" to ConflictResolution.OVERWRITE_WITH_CLOUD,
        )
      val summary = recoveryUseCase.applyRecovery(scan.items, resolutions)
      assertEquals(1, summary.updatedFromCloudCount)
      assertEquals(1, summary.skippedLocalNewerCount)
      assertEquals(1, summary.skippedDuplicatesCount)
      assertEquals("Local edited newer", clipRepo.getClipById(localNewer.clipId)!!.content)
      assertEquals("Cloud newer conflict edit", clipRepo.getClipById(conflictClip.clipId)!!.content)
      assertNotNull(clipRepo.findByRemoteId("tg:1:40"))

      // Re-applying same recovery scan produces zero duplicates
      val secondScan = recoveryUseCase.scanProviders()
      assertEquals(0, secondScan.newRemoteCount)

      // StorageCapacityUseCase thresholds & deletion safety guard
      val storageUseCase = StorageCapacityUseCase(context, clipRepo, syncQueueRepo, prefs)
      val unsyncedLocal =
        ClipItem(id = 10L, content = "Unsynced", contentHash = "u1", syncStatus = ClipSyncState.LOCAL_ONLY)
      val syncedRemote =
        ClipItem(
          id = 11L,
          content = "Synced",
          contentHash = "s1",
          syncStatus = ClipSyncState.SYNCED,
          telegramMessageId = "tg:1:10",
        )
      assertFalse(storageUseCase.isSafeToDeleteWithoutCloudLoss(unsyncedLocal))
      assertTrue(storageUseCase.isSafeToDeleteWithoutCloudLoss(syncedRemote))

      val stats = storageUseCase.computeStats()
      assertTrue(stats.activeClipsCount >= 4)
      assertEquals(StorageThresholdLevel.NORMAL, stats.thresholdLevel)
    }

  @Test
  fun `compose ui renders recovery center storage capacity and settings ime sensitive controls`() {
    var scanClicked = false
    val recoveryItem =
      CloudRecoveryItem(
        key = "tg:1:99:h1",
        providerId = SyncProviderId.TELEGRAM,
        remoteId = "tg:1:99",
        content = "Recoverable snippet",
        contentHash = "hash12345",
        source = ClipSource.TELEGRAM,
        matchStatus = RecoveryMatchStatus.NEW_REMOTE,
      )
    val scanResult =
      RecoveryScanResult(
        items = listOf(recoveryItem),
        newRemoteCount = 1,
        alreadySyncedCount = 0,
        localNewerCount = 0,
        conflictCount = 0,
      )
    val health =
      listOf(ProviderHealthSummary(SyncProviderId.DISCORD, isConnected = true, statusMessage = "Private Vault #smart-clipboard-vault"))

    composeTestRule.setContent {
      SmartClipboardTheme {
        RecoveryCenterScreen(
          healthSummaries = health,
          scanResult = scanResult,
          selectedRemoteIds = setOf("tg:1:99"),
          conflictResolutions = emptyMap(),
          isBusy = false,
          onScanAll = { scanClicked = true },
          onToggleSelect = {},
          onSelectMissingOnly = {},
          onSetConflictResolution = { _, _ -> },
          onRestoreSelected = {},
          onRestoreAllMissing = {},
        )
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("recovery_scan_btn").performClick()
    assertTrue(scanClicked)
    composeTestRule.onNodeWithTag("health_card_discord").assertIsDisplayed()
    composeTestRule.onNodeWithTag("recovery_lazy_list").performScrollToNode(hasText("Recoverable snippet"))
    composeTestRule.onNodeWithText("Recoverable snippet").assertIsDisplayed()
  }

  @Test
  fun `compose ui renders storage capacity manager and settings checkpoint 10 controls`() {
    var quotaUpdated = 0
    val storageStats =
      StorageCapacityStats(
        totalClipsCount = 12,
        activeClipsCount = 10,
        trashedClipsCount = 2,
        sensitiveClipsCount = 1,
        pendingSyncCount = 1,
        failedSyncCount = 0,
        totalContentBytes = 4096L,
        activeContentBytes = 3500L,
        trashedBytes = 596L,
        databaseFileBytes = 8192L,
        softQuotaMb = 50,
        softQuotaBytes = 50L * 1024L * 1024L,
        usagePercent = 88,
        thresholdLevel = StorageThresholdLevel.HIGH_85,
        largestClips = listOf(ClipItem(id = 5L, content = "Large snippet", contentHash = "big", contentSizeBytes = 2048L)),
      )

    composeTestRule.setContent {
      SmartClipboardTheme {
        StorageCapacityScreen(
          stats = storageStats,
          settings = UserSettings(storageSoftQuotaMb = 50),
          onUpdateQuotaMb = { quotaUpdated = it },
          onUpdateThresholds = { _, _, _ -> },
          onDeleteSelected = { _, _ -> },
          onEmptyTrash = {},
          onBackupNow = {},
        )
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("storage_progress_bar").assertIsDisplayed()
    composeTestRule.onNodeWithTag("storage_lazy_list").performScrollToNode(hasText("25 MB"))
    composeTestRule.onNodeWithText("25 MB").performClick()
    assertEquals(25, quotaUpdated)
  }

  @Test
  fun `settings screen renders optional ime and sensitive content controls cleanly`() {
    composeTestRule.setContent {
      SmartClipboardTheme {
        SettingsScreen(
          settings = UserSettings(),
          onThemeChange = {},
          onLanguageChange = {},
          onAppLockToggle = {},
          onAutoCaptureToggle = {},
          onRetentionDaysChange = {},
          onClearSystemClipboard = {},
          onWipeAllData = {},
        )
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("instant_autosave_switch").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("open_ime_settings_btn").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("detect_sensitive_switch").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("block_sensitive_sync_switch").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("settings_storage_btn").performScrollTo().assertIsDisplayed()
  }
}
