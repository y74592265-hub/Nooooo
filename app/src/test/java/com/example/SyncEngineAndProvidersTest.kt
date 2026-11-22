package com.example

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.clipboard.SystemClipboardHelper
import com.example.core.preferences.AppPreferencesRepository
import com.example.core.security.KeystoreCryptoManager
import com.example.core.sync.SyncEngine
import com.example.data.local.SmartClipboardDatabase
import com.example.data.repository.CategoryRepositoryImpl
import com.example.data.repository.ClipRepositoryImpl
import com.example.data.repository.SyncLogRepositoryImpl
import com.example.domain.model.ClipSource
import com.example.domain.model.DiscordConfig
import com.example.domain.model.GoogleDriveConfig
import com.example.domain.model.SyncProviderId
import com.example.domain.model.SyncStatus
import com.example.domain.model.TelegramConfig
import com.example.domain.usecase.ExportImportUseCase
import com.example.integrations.ProviderResult
import com.example.integrations.discord.DiscordApiService
import com.example.integrations.discord.DiscordAuthor
import com.example.integrations.discord.DiscordChannel
import com.example.integrations.discord.DiscordMessageDto
import com.example.integrations.discord.DiscordMessageRequest
import com.example.integrations.discord.DiscordProvider
import com.example.integrations.discord.DiscordWebhookPayload
import com.example.integrations.gdrive.DriveCreateFolderRequest
import com.example.integrations.gdrive.DriveFileDto
import com.example.integrations.gdrive.DriveFileListResponse
import com.example.integrations.gdrive.GoogleDriveApiService
import com.example.integrations.gdrive.GoogleDriveAuthHelper
import com.example.integrations.gdrive.GoogleDriveProvider
import com.example.integrations.gdrive.GoogleUserInfoResponse
import com.example.integrations.telegram.TelegramApiService
import com.example.integrations.telegram.TelegramChat
import com.example.integrations.telegram.TelegramMessage
import com.example.integrations.telegram.TelegramProvider
import com.example.integrations.telegram.TelegramResponse
import com.example.integrations.telegram.TelegramSendMessageRequest
import com.example.integrations.telegram.TelegramUpdate
import com.example.integrations.telegram.TelegramUser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SyncEngineAndProvidersTest {

  private lateinit var context: Context
  private lateinit var db: SmartClipboardDatabase
  private lateinit var crypto: KeystoreCryptoManager
  private lateinit var prefs: AppPreferencesRepository
  private lateinit var clipRepo: ClipRepositoryImpl
  private lateinit var categoryRepo: CategoryRepositoryImpl
  private lateinit var syncLogRepo: SyncLogRepositoryImpl
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
    categoryRepo.ensureDefaultCategories()
    setNetworkOnline(true)
  }

  @After
  fun tearDown() {
    db.close()
  }

  private fun setNetworkOnline(online: Boolean) {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val shadowCm = Shadows.shadowOf(cm)
    if (!online) {
      shadowCm.setDefaultNetworkActive(false)
      val activeNet = cm.activeNetwork
      if (activeNet != null) {
        shadowCm.setNetworkCapabilities(activeNet, ShadowNetworkCapabilities.newInstance())
      }
    } else {
      val net = cm.activeNetwork
      if (net != null) {
        val caps = ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowCm.setNetworkCapabilities(net, caps)
      }
    }
  }

  @Test
  fun `telegram pull deduplicates updates and handles API errors without breaking local clips`() = runTest {
    clipRepo.saveNewOrDeduplicate("", "Local safe clip", source = ClipSource.MANUAL)

    val failingTelegramApi =
      object : TelegramApiService {
        override suspend fun getMe(token: String) =
          Response.error<TelegramResponse<TelegramUser>>(401, "Unauthorized".toResponseBody())

        override suspend fun sendMessage(token: String, body: TelegramSendMessageRequest) =
          Response.error<TelegramResponse<TelegramMessage>>(500, "Server error".toResponseBody())

        override suspend fun getUpdates(token: String, offset: Long?, limit: Int, timeout: Int) =
          Response.error<TelegramResponse<List<TelegramUpdate>>>(401, "Unauthorized".toResponseBody())
      }

    prefs.saveTelegramConfig(TelegramConfig(enabled = true, botToken = "bad_token", chatId = "12345"))
    val tgProvider = TelegramProvider(failingTelegramApi, prefs, categoryRepo)
    val testRes = tgProvider.testConnection()
    assertTrue(testRes is ProviderResult.Error)

    val engine =
      SyncEngine(context, setOf(tgProvider), clipRepo, categoryRepo, syncLogRepo, prefs)
    engine.syncAll()

    // Local clip remains untouched despite Telegram failure, and error is logged
    assertEquals(1, clipRepo.getAllActiveClipsOnce().size)
    val logs = syncLogRepo.observeRecentLogs().first()
    assertTrue(logs.any { it.provider == SyncProviderId.TELEGRAM && it.status == SyncStatus.ERROR })

    // Now test successful Telegram pull & duplicate prevention
    val okTelegramApi =
      object : TelegramApiService {
        override suspend fun getMe(token: String) =
          Response.success(TelegramResponse(ok = true, result = TelegramUser(1, "smart_clip_bot")))

        override suspend fun sendMessage(token: String, body: TelegramSendMessageRequest) =
          Response.success(
            TelegramResponse(
              ok = true,
              result = TelegramMessage(10L, TelegramChat(12345L), body.text),
            )
          )

        override suspend fun getUpdates(token: String, offset: Long?, limit: Int, timeout: Int) =
          Response.success(
            TelegramResponse(
              ok = true,
              result =
                listOf(
                  TelegramUpdate(100L, TelegramMessage(50L, TelegramChat(12345L), "From Telegram #Work"))
                ),
            )
          )
      }
    val workingTg = TelegramProvider(okTelegramApi, prefs, categoryRepo)
    val engine2 = SyncEngine(context, setOf(workingTg), clipRepo, categoryRepo, syncLogRepo, prefs)
    engine2.syncAll()
    engine2.syncAll() // Second sync must not duplicate the pulled message

    val clips = clipRepo.getAllActiveClipsOnce()
    assertEquals(2, clips.size)
    assertTrue(clips.any { it.content == "From Telegram #Work" })
  }

  @Test
  fun `discord separates webhook send from bot receive and handles failure gracefully`() = runTest {
    prefs.saveDiscordConfig(
      DiscordConfig(
        enabled = true,
        webhookUrl = "https://discord.com/api/webhooks/111/abc",
        botToken = "",
        channelId = "",
      )
    )
    val failingWebhookDiscord =
      object : DiscordApiService {
        override suspend fun executeWebhook(webhookUrl: String, payload: DiscordWebhookPayload) =
          Response.error<Unit>(429, "Rate limited".toResponseBody())

        override suspend fun getWebhookInfo(webhookUrl: String) =
          Response.error<DiscordChannel>(404, "Unknown".toResponseBody())

        override suspend fun getChannel(botAuthorization: String, channelId: String) =
          Response.error<DiscordChannel>(401, "Unauthorized".toResponseBody())

        override suspend fun sendChannelMessage(
          botAuthorization: String,
          channelId: String,
          body: DiscordMessageRequest,
        ) = Response.error<DiscordMessageDto>(401, "Unauthorized".toResponseBody())

        override suspend fun getChannelMessages(
          botAuthorization: String,
          channelId: String,
          afterMessageId: String?,
          limit: Int,
        ) = Response.success(emptyList<DiscordMessageDto>())
      }

    val discordProvider = DiscordProvider(failingWebhookDiscord, prefs, categoryRepo)
    // Pull succeeds cleanly with empty list when only webhook is configured
    val pullOnlyWebhook = discordProvider.pullClips()
    assertTrue(pullOnlyWebhook is ProviderResult.Success)
    assertEquals(0, (pullOnlyWebhook as ProviderResult.Success).data.size)

    // Push fails cleanly with retryable=true on 429
    clipRepo.saveNewOrDeduplicate("", "Discord test snippet")
    val pushFail = discordProvider.pushClips(clipRepo.getAllActiveClipsOnce())
    assertTrue(pushFail is ProviderResult.Error)
    assertTrue((pushFail as ProviderResult.Error).isRetryable)

    // Bot receive works when Bot Token + Channel ID are configured
    prefs.saveDiscordConfig(
      DiscordConfig(
        enabled = true,
        webhookUrl = "https://discord.com/api/webhooks/111/abc",
        botToken = "bot_secret_xyz",
        channelId = "999",
      )
    )
    val okDiscordApi =
      object : DiscordApiService {
        override suspend fun executeWebhook(webhookUrl: String, payload: DiscordWebhookPayload) =
          Response.success(Unit)

        override suspend fun getWebhookInfo(webhookUrl: String) =
          Response.success(DiscordChannel("999", "clips"))

        override suspend fun getChannel(botAuthorization: String, channelId: String) =
          Response.success(DiscordChannel("999", "clips"))

        override suspend fun sendChannelMessage(
          botAuthorization: String,
          channelId: String,
          body: DiscordMessageRequest,
        ) = Response.success(DiscordMessageDto("1", body.content))

        override suspend fun getChannelMessages(
          botAuthorization: String,
          channelId: String,
          afterMessageId: String?,
          limit: Int,
        ) =
          Response.success(
            listOf(
              DiscordMessageDto("201", "Clip from Discord", DiscordAuthor("u1", "alice", bot = false)),
              DiscordMessageDto("202", "Bot echo ignored", DiscordAuthor("b1", "bot", bot = true)),
            )
          )
      }
    val workingDiscord = DiscordProvider(okDiscordApi, prefs, categoryRepo)
    val pulled = workingDiscord.pullClips() as ProviderResult.Success
    assertEquals(1, pulled.data.size)
    assertEquals("Clip from Discord", pulled.data.first().content)
  }

  @Test
  fun `google drive failure does not affect local data and offline sync reports cleanly`() = runTest {
    clipRepo.saveNewOrDeduplicate("", "Important local clip")
    val exportUseCase = ExportImportUseCase(clipRepo, categoryRepo, crypto, json)
    val failingDriveApi =
      object : GoogleDriveApiService {
        override suspend fun getUserInfo(bearerToken: String) =
          Response.error<GoogleUserInfoResponse>(401, "Invalid".toResponseBody())

        override suspend fun listFiles(
          bearerToken: String,
          spaces: String,
          query: String?,
          fields: String,
          orderBy: String,
          pageSize: Int,
        ) = Response.error<DriveFileListResponse>(500, "Drive error".toResponseBody())

        override suspend fun createFolder(bearerToken: String, body: DriveCreateFolderRequest) =
          Response.error<DriveFileDto>(500, "Err".toResponseBody())

        override suspend fun uploadBackupFile(
          bearerToken: String,
          metadata: RequestBody,
          file: MultipartBody.Part,
        ) = Response.error<DriveFileDto>(500, "Err".toResponseBody())

        override suspend fun downloadFileContent(bearerToken: String, fileId: String) =
          Response.error<okhttp3.ResponseBody>(404, "Not found".toResponseBody())
      }

    prefs.saveGoogleDriveConfig(GoogleDriveConfig(enabled = true, accountEmail = "user@example.com"))
    val driveProvider =
      GoogleDriveProvider(failingDriveApi, GoogleDriveAuthHelper(context), prefs, exportUseCase)

    // Without OAuth token in memory or Play Services consent, testConnection fails gracefully
    val connRes = driveProvider.testConnection()
    assertTrue(connRes is ProviderResult.Error)
    assertEquals(1, clipRepo.getAllActiveClipsOnce().size)

    // Simulate offline state on SyncEngine
    setNetworkOnline(false)
    val engine = SyncEngine(context, setOf(driveProvider), clipRepo, categoryRepo, syncLogRepo, prefs)
    val offlineResult = engine.syncAll()
    assertFalse(offlineResult)
    assertEquals(SyncStatus.ERROR, engine.state.value.lastOverallStatus)
    assertEquals(1, clipRepo.getAllActiveClipsOnce().size)
  }
}
