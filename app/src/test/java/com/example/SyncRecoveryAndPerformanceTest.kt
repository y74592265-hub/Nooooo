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
import com.example.data.repository.SyncQueueRepositoryImpl
import com.example.di.AppModule
import com.example.domain.model.ClipItem
import com.example.domain.model.ClipSource
import com.example.domain.model.DiscordConfig
import com.example.domain.model.GoogleDriveConfig
import com.example.domain.model.SyncProviderId
import com.example.domain.model.SyncStatus
import com.example.domain.model.TelegramConfig
import com.example.domain.usecase.ExportImportUseCase
import com.example.domain.usecase.SearchFilter
import com.example.domain.usecase.SmartSearchUseCase
import com.example.integrations.IntegrationProvider
import com.example.integrations.ProviderResult
import com.example.integrations.PulledClip
import com.example.integrations.discord.DiscordApiService
import com.example.integrations.discord.DiscordBotUser
import com.example.integrations.discord.DiscordChannel
import com.example.integrations.discord.DiscordCreateChannelRequest
import com.example.integrations.discord.DiscordGuild
import com.example.integrations.discord.DiscordProvider
import com.example.integrations.discord.DiscordRole
import com.example.integrations.gdrive.DriveCreateFolderRequest
import com.example.integrations.gdrive.DriveFileDto
import com.example.integrations.gdrive.DriveFileListResponse
import com.example.integrations.gdrive.GoogleDriveApiService
import com.example.integrations.gdrive.GoogleDriveAuthHelper
import com.example.integrations.gdrive.GoogleDriveProvider
import com.example.integrations.gdrive.GoogleUserInfoResponse
import com.example.integrations.telegram.TelegramApiService
import com.example.integrations.telegram.TelegramChat
import com.example.integrations.telegram.TelegramDeleteMessageRequest
import com.example.integrations.telegram.TelegramMessage
import com.example.integrations.telegram.TelegramProvider
import com.example.integrations.telegram.TelegramResponse
import com.example.integrations.telegram.TelegramSendMessageRequest
import com.example.integrations.telegram.TelegramUpdate
import com.example.integrations.telegram.TelegramUser
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
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
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SyncRecoveryAndPerformanceTest {

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
    db =
      Room.inMemoryDatabaseBuilder(context, SmartClipboardDatabase::class.java)
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
  fun `single click and rapid repeated concurrent syncAll clicks are single-flight safe without duplicates or crashes`() =
    runBlocking {
      clipRepo.saveNewOrDeduplicate("T1", "Clip one for single-flight test")
      val pushInvocations = AtomicInteger(0)

      val slowProvider =
        object : IntegrationProvider {
          override val providerId = SyncProviderId.TELEGRAM
          override suspend fun isConfigured() = true
          override suspend fun testConnection() = ProviderResult.Success("ok")
          override suspend fun pullClips(): ProviderResult<List<PulledClip>> {
            delay(80)
            return ProviderResult.Success(emptyList())
          }

          override suspend fun pushClips(clips: List<ClipItem>): ProviderResult<Int> {
            pushInvocations.incrementAndGet()
            delay(80)
            return ProviderResult.Success(clips.size)
          }
        }

      val engine =
        SyncEngine(context, setOf(slowProvider), clipRepo, categoryRepo, syncLogRepo, syncQueueRepo, prefs)

      // Launch 8 rapid concurrent sync calls
      val results =
        (1..8)
          .map { async(Dispatchers.Default) { engine.syncAll() } }
          .awaitAll()

      assertTrue(results.all { it })
      assertEquals(1, pushInvocations.get())
      assertFalse(engine.state.value.isSyncing)
      assertEquals(SyncStatus.SUCCESS, engine.state.value.lastOverallStatus)
    }

  @Test
  fun `weak timeout network produces retryable error and subsequent retry recovers cleanly without data loss`() =
    runTest {
      val saved = clipRepo.saveNewOrDeduplicate("Critical", "Keep me safe during network timeout")
      val shouldTimeout = AtomicInteger(1)

      val flakyProvider =
        object : IntegrationProvider {
          override val providerId = SyncProviderId.TELEGRAM
          override suspend fun isConfigured() = true
          override suspend fun testConnection() = ProviderResult.Success("ok")
          override suspend fun pullClips(): ProviderResult<List<PulledClip>> {
            return if (shouldTimeout.getAndDecrement() > 0) {
              try {
                throw SocketTimeoutException("Read timed out on weak cellular network")
              } catch (e: SocketTimeoutException) {
                ProviderResult.Error("Timeout: ${e.message}", isRetryable = true)
              }
            } else {
              ProviderResult.Success(
                listOf(PulledClip(content = "Recovered after retry", source = ClipSource.TELEGRAM, remoteId = "tg:1:77"))
              )
            }
          }

          override suspend fun pushClips(clips: List<ClipItem>): ProviderResult<Int> =
            ProviderResult.Success(clips.size)
        }

      val engine =
        SyncEngine(context, setOf(flakyProvider), clipRepo, categoryRepo, syncLogRepo, syncQueueRepo, prefs)

      // Attempt 1: fails due to timeout -> returns false (should retry)
      val firstAttempt = engine.syncAll()
      assertFalse(firstAttempt)
      assertFalse(engine.state.value.isSyncing)
      assertEquals(SyncStatus.PARTIAL_ERROR, engine.state.value.lastOverallStatus)
      // Local clip preserved intact
      assertEquals("Keep me safe during network timeout", clipRepo.getClipById(saved.clipId)?.content)

      // Attempt 2 (Retry): succeeds and pulls remote clip
      val retryAttempt = engine.syncAll()
      assertTrue(retryAttempt)
      assertFalse(engine.state.value.isSyncing)
      assertEquals(SyncStatus.SUCCESS, engine.state.value.lastOverallStatus)
      assertEquals(2, clipRepo.getAllActiveClipsOnce().size)
    }

  @Test
  fun `invalid tokens and control characters in Telegram and Discord do not throw uncaught exceptions`() =
    runTest {
      val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
      val client = OkHttpClient()
      val tgApi =
        Retrofit.Builder()
          .baseUrl("https://api.telegram.org/")
          .client(client)
          .addConverterFactory(MoshiConverterFactory.create(moshi))
          .build()
          .create(TelegramApiService::class.java)
      val dcApi =
        Retrofit.Builder()
          .baseUrl("https://discord.com/")
          .client(client)
          .addConverterFactory(MoshiConverterFactory.create(moshi))
          .build()
          .create(DiscordApiService::class.java)

      // Whitespace/newline token on Telegram
      prefs.saveTelegramConfig(
        TelegramConfig(enabled = true, botToken = "123456:bad\ntoken", chatId = "100")
      )
      val tg = TelegramProvider(tgApi, prefs, categoryRepo)
      assertTrue(tg.testConnection() is ProviderResult.Error)
      assertTrue(tg.detectChatIdFromUpdates("123456:bad token") is ProviderResult.Error)

      // Newline / non-ASCII control characters in Discord botToken & invalid webhook URL
      prefs.saveDiscordConfig(
        DiscordConfig(
          enabled = true,
          webhookUrl = "https://evil.example.com/api/webhooks/1/2",
          botToken = "bad\ntoken\u0000",
          channelId = "123",
        )
      )
      val dc = DiscordProvider(dcApi, prefs, categoryRepo)
      assertTrue(dc.testConnection() is ProviderResult.Error)
      assertTrue(dc.pushClips(emptyList()) is ProviderResult.Error)
      assertFalse(dc.isConfigured())
    }

  @Test
  fun `coroutine cancellation during sync simulates app close and subsequent reopen sync succeeds cleanly`() =
    runBlocking {
      clipRepo.saveNewOrDeduplicate("Local", "Preserved across cancellation")
      val slowCancellableProvider =
        object : IntegrationProvider {
          override val providerId = SyncProviderId.DISCORD
          override suspend fun isConfigured() = true
          override suspend fun testConnection() = ProviderResult.Success("ok")
          override suspend fun pullClips(): ProviderResult<List<PulledClip>> {
            delay(1_500)
            return ProviderResult.Success(emptyList())
          }

          override suspend fun pushClips(clips: List<ClipItem>): ProviderResult<Int> =
            ProviderResult.Success(clips.size)
        }

      val engine =
        SyncEngine(
          context,
          setOf(slowCancellableProvider),
          clipRepo,
          categoryRepo,
          syncLogRepo,
          syncQueueRepo,
          prefs,
        )

      val job = launch(Dispatchers.Default) { engine.syncAll() }
      delay(60)
      assertTrue(engine.state.value.isSyncing)

      // Simulate app/ViewModel cancellation mid-sync
      job.cancel(CancellationException("App closed mid-sync"))
      job.join()

      // Crucial: isSyncing must be reset to false and lock released!
      assertFalse(engine.state.value.isSyncing)

      // Simulate reopen & retry with healthy provider
      val fastProvider =
        object : IntegrationProvider {
          override val providerId = SyncProviderId.DISCORD
          override suspend fun isConfigured() = true
          override suspend fun testConnection() = ProviderResult.Success("ok")
          override suspend fun pullClips() = ProviderResult.Success(emptyList<PulledClip>())
          override suspend fun pushClips(clips: List<ClipItem>) = ProviderResult.Success(clips.size)
        }
      val engineAfterReopen =
        SyncEngine(
          context,
          setOf(fastProvider),
          clipRepo,
          categoryRepo,
          syncLogRepo,
          syncQueueRepo,
          prefs,
        )
      assertTrue(engineAfterReopen.syncAll())
      assertEquals(SyncStatus.SUCCESS, engineAfterReopen.state.value.lastOverallStatus)
      assertEquals(1, clipRepo.getAllActiveClipsOnce().size)
    }

  @Test
  fun `zero local data loss when all three providers Telegram Discord and GoogleDrive fail simultaneously`() =
    runTest {
      val local1 = clipRepo.saveNewOrDeduplicate("Alpha", "Keep alpha")
      val local2 = clipRepo.saveNewOrDeduplicate("Secret", "Keep secret", isSensitive = true)

      val badTg =
        object : TelegramApiService {
          override suspend fun getMe(token: String) =
            Response.error<TelegramResponse<TelegramUser>>(500, "err".toResponseBody())
          override suspend fun sendMessage(token: String, body: TelegramSendMessageRequest) =
            Response.error<TelegramResponse<TelegramMessage>>(500, "err".toResponseBody())
          override suspend fun getUpdates(token: String, offset: Long?, limit: Int, timeout: Int) =
            Response.error<TelegramResponse<List<TelegramUpdate>>>(500, "err".toResponseBody())
          override suspend fun deleteMessage(token: String, body: TelegramDeleteMessageRequest) =
            Response.error<TelegramResponse<Boolean>>(500, "err".toResponseBody())
        }
      val badDc =
        object : DiscordApiService {
          override suspend fun executeWebhook(webhookUrl: String, payload: com.example.integrations.discord.DiscordWebhookPayload) =
            Response.error<Unit>(503, "down".toResponseBody())
          override suspend fun getWebhookInfo(webhookUrl: String) =
            Response.error<DiscordChannel>(503, "down".toResponseBody())
          override suspend fun getCurrentBotUser(botAuthorization: String) =
            Response.error<DiscordBotUser>(503, "down".toResponseBody())
          override suspend fun getBotGuilds(botAuthorization: String) =
            Response.error<List<DiscordGuild>>(503, "down".toResponseBody())
          override suspend fun getGuildChannels(botAuthorization: String, guildId: String) =
            Response.error<List<DiscordChannel>>(503, "down".toResponseBody())
          override suspend fun getGuildRoles(botAuthorization: String, guildId: String) =
            Response.error<List<DiscordRole>>(503, "down".toResponseBody())
          override suspend fun createGuildChannel(botAuthorization: String, guildId: String, body: DiscordCreateChannelRequest) =
            Response.error<DiscordChannel>(503, "down".toResponseBody())
          override suspend fun getChannel(botAuthorization: String, channelId: String) =
            Response.error<DiscordChannel>(503, "down".toResponseBody())
          override suspend fun sendChannelMessage(botAuthorization: String, channelId: String, body: com.example.integrations.discord.DiscordMessageRequest) =
            Response.error<com.example.integrations.discord.DiscordMessageDto>(503, "down".toResponseBody())
          override suspend fun getChannelMessages(botAuthorization: String, channelId: String, afterMessageId: String?, limit: Int, beforeMessageId: String?) =
            Response.error<List<com.example.integrations.discord.DiscordMessageDto>>(503, "down".toResponseBody())
          override suspend fun deleteChannelMessage(botAuthorization: String, channelId: String, messageId: String) =
            Response.error<Unit>(503, "down".toResponseBody())
        }
      val badDrive =
        object : GoogleDriveApiService {
          override suspend fun getUserInfo(bearerToken: String) =
            Response.error<GoogleUserInfoResponse>(500, "err".toResponseBody())
          override suspend fun listFiles(bearerToken: String, spaces: String, query: String?, fields: String, orderBy: String, pageSize: Int) =
            Response.error<DriveFileListResponse>(500, "err".toResponseBody())
          override suspend fun createFolder(bearerToken: String, body: DriveCreateFolderRequest) =
            Response.error<DriveFileDto>(500, "err".toResponseBody())
          override suspend fun uploadBackupFile(bearerToken: String, metadata: RequestBody, file: MultipartBody.Part) =
            Response.error<DriveFileDto>(500, "err".toResponseBody())
          override suspend fun startResumableUpload(bearerToken: String, contentType: String, contentLength: Long, metadata: RequestBody) =
            Response.error<Unit>(500, "err".toResponseBody())
          override suspend fun uploadResumableContent(uploadSessionUrl: String, content: RequestBody) =
            Response.error<DriveFileDto>(500, "err".toResponseBody())
          override suspend fun downloadFileContent(bearerToken: String, fileId: String) =
            Response.error<okhttp3.ResponseBody>(500, "err".toResponseBody())
          override suspend fun deleteFile(bearerToken: String, fileId: String) =
            Response.error<Unit>(500, "err".toResponseBody())
        }

      prefs.saveTelegramConfig(TelegramConfig(enabled = true, botToken = "123:abc", chatId = "1"))
      prefs.saveDiscordConfig(DiscordConfig(enabled = true, webhookUrl = "https://discord.com/api/webhooks/1/abc"))
      prefs.saveGoogleDriveConfig(GoogleDriveConfig(enabled = true, accountEmail = "u@test.com"))

      val exportUseCase = ExportImportUseCase(clipRepo, categoryRepo, crypto, json)
      val engine =
        SyncEngine(
          context,
          setOf(
            TelegramProvider(badTg, prefs, categoryRepo),
            DiscordProvider(badDc, prefs, categoryRepo),
            GoogleDriveProvider(badDrive, GoogleDriveAuthHelper(context), prefs, exportUseCase),
          ),
          clipRepo,
          categoryRepo,
          syncLogRepo,
          syncQueueRepo,
          prefs,
        )

      engine.syncAll()
      assertFalse(engine.state.value.isSyncing)
      assertEquals(SyncStatus.PARTIAL_ERROR, engine.state.value.lastOverallStatus)

      val after = clipRepo.getAllActiveClipsOnce()
      assertEquals(2, after.size)
      assertEquals("Keep alpha", clipRepo.getClipById(local1.clipId)?.content)
      assertEquals("Keep secret", clipRepo.getClipById(local2.clipId)?.content)
    }

  @Test
  fun `updateRemoteId preserves clip updatedAt timestamp during sync`() = runTest {
    val saved = clipRepo.saveNewOrDeduplicate("Pinned note", "Do not reorder on sync")
    val before = clipRepo.getClipById(saved.clipId)!!

    delay(25)
    clipRepo.updateRemoteId(saved.clipId, "pushed:telegram:1234")
    val after = clipRepo.getClipById(saved.clipId)!!

    assertEquals("pushed:telegram:1234", after.remoteId)
    assertEquals(before.updatedAt, after.updatedAt)
  }

  @Test
  fun `large dataset 1000 clips observe decrypt search and sync benchmark stays fast`() = runTest {
    val insertMs = measureTimeMillis {
      for (i in 1..1_000) {
        clipRepo.saveNewOrDeduplicate(
          title = "Clip #$i",
          content = if (i % 5 == 0) "secret_api_token_value_$i" else "Clipboard snippet number $i https://example.com/$i",
          tags = listOf("tag${i % 10}", "batch"),
          isSensitive = (i % 5 == 0),
        )
      }
    }

    var observedCount = 0
    val observeDecryptMs = measureTimeMillis {
      val list = clipRepo.observeActiveClips().first()
      observedCount = list.size
    }
    assertEquals(1_000, observedCount)

    val allClips = clipRepo.getAllActiveClipsOnce()
    val searchUseCase = SmartSearchUseCase()
    var matchedCount = 0
    val searchMs = measureTimeMillis {
      matchedCount = searchUseCase(allClips, SearchFilter(query = "secret_api_token")).size
    }
    assertEquals(200, matchedCount)

    val noopProvider =
      object : IntegrationProvider {
        override val providerId = SyncProviderId.TELEGRAM
        override suspend fun isConfigured() = true
        override suspend fun testConnection() = ProviderResult.Success("ok")
        override suspend fun pullClips() = ProviderResult.Success(emptyList<PulledClip>())
        override suspend fun pushClips(clips: List<ClipItem>) = ProviderResult.Success(clips.size)
      }
    val engine =
      SyncEngine(context, setOf(noopProvider), clipRepo, categoryRepo, syncLogRepo, syncQueueRepo, prefs)
    val syncMs = measureTimeMillis {
      assertTrue(engine.syncAll(pushRecentLimit = 50))
    }

    println(
      "PERF_BENCHMARK_1000_CLIPS insertMs=$insertMs observeDecryptMs=$observeDecryptMs searchMs=$searchMs syncMs=$syncMs"
    )
    assertTrue("Expected search across 1000 clips under 2000ms, was $searchMs ms", searchMs < 2_000)
    assertTrue("Expected observe+decrypt of 1000 clips under 5000ms, was $observeDecryptMs ms", observeDecryptMs < 5_000)
  }

  @Test
  fun `okHttpClient disables URL and token logging to keep Telegram and Discord secrets out of Logcat`() {
    val client = AppModule.provideOkHttpClient()
    val loggingInterceptors = client.interceptors.filterIsInstance<HttpLoggingInterceptor>()
    assertTrue(loggingInterceptors.isNotEmpty())
    assertTrue(loggingInterceptors.all { it.level == HttpLoggingInterceptor.Level.NONE })
  }
}
