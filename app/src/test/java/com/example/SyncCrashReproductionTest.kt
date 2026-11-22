package com.example

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import com.example.domain.model.ClipItem
import com.example.domain.model.SyncProviderId
import com.example.domain.model.TelegramConfig
import com.example.integrations.IntegrationProvider
import com.example.integrations.ProviderResult
import com.example.integrations.PulledClip
import com.example.integrations.discord.DiscordApiService
import com.example.integrations.discord.DiscordWebhookPayload
import com.example.integrations.telegram.TelegramApiService
import com.example.integrations.telegram.TelegramProvider
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SyncCrashReproductionTest {

  @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun `clicking nav_backup_sync, sync_all_now_btn, and open_integrations_btn in MainActivity works without crash in Arabic RTL`() {
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("nav_backup_sync").performClick()
    composeRule.waitForIdle()

    // Single click on Sync button must not crash
    composeRule.onNodeWithTag("sync_all_now_btn").assertIsDisplayed().performClick()
    composeRule.waitForIdle()

    // Rapid repeated clicks on Sync button must not crash
    repeat(3) {
      composeRule.onNodeWithTag("sync_all_now_btn").performClick()
    }
    composeRule.waitForIdle()

    // Navigating to IntegrationsScreen (which also uses rememberLauncherForActivityResult) must not crash
    composeRule.onNodeWithTag("open_integrations_btn").performScrollTo().performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("telegram_token_input").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun `telegram real bot token with colon resolves properly and HTML 502 is caught safely without crash`() =
    runTest {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val db =
        Room.inMemoryDatabaseBuilder(context, SmartClipboardDatabase::class.java)
          .allowMainThreadQueries()
          .build()
      try {
        val crypto = KeystoreCryptoManager()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val prefs = AppPreferencesRepository(context, crypto, json)
        prefs.clearAllPreferences()
        val categoryRepo = CategoryRepositoryImpl(db.categoryDao())
        categoryRepo.ensureDefaultCategories()

        var capturedUrlPath: String? = null
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val fakeClient =
          OkHttpClient.Builder()
            .addInterceptor(
              Interceptor { chain ->
                capturedUrlPath = chain.request().url.encodedPath
                okhttp3.Response.Builder()
                  .code(502)
                  .message("Bad Gateway")
                  .protocol(Protocol.HTTP_1_1)
                  .request(chain.request())
                  .body("<html>502 Bad Gateway</html>".toResponseBody("text/html".toMediaType()))
                  .build()
              }
            )
            .build()

        val tgApi =
          Retrofit.Builder()
            .baseUrl("https://api.telegram.org/")
            .client(fakeClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(TelegramApiService::class.java)

        val tgProvider = TelegramProvider(tgApi, prefs, categoryRepo)
        val res = tgProvider.detectChatIdFromUpdates("123456:ABC-DEF_xyz")
        assertEquals("/bot123456:ABC-DEF_xyz/getUpdates", capturedUrlPath)
        assertTrue(res is ProviderResult.Error)
      } finally {
        db.close()
      }
    }

  @Test
  fun `discord executeWebhook with real Retrofit MoshiConverterFactory handles 204 No Content cleanly`() =
    runTest {
      val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
      val client204 =
        OkHttpClient.Builder()
          .addInterceptor(
            Interceptor { chain ->
              okhttp3.Response.Builder()
                .code(204)
                .message("No Content")
                .protocol(Protocol.HTTP_1_1)
                .request(chain.request())
                .body(ByteArray(0).toResponseBody(null))
                .build()
            }
          )
          .build()

      val discordApi =
        Retrofit.Builder()
          .baseUrl("https://discord.com/")
          .client(client204)
          .addConverterFactory(MoshiConverterFactory.create(moshi))
          .build()
          .create(DiscordApiService::class.java)

      val resp =
        discordApi.executeWebhook(
          "https://discord.com/api/webhooks/123/token",
          DiscordWebhookPayload("hello"),
        )
      assertTrue(resp.isSuccessful)
    }

  @Test
  fun `throwing provider in syncSingleProvider and syncAll is isolated without crashing`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val net = cm.activeNetwork
    if (net != null) {
      val caps = ShadowNetworkCapabilities.newInstance()
      Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      Shadows.shadowOf(cm).setNetworkCapabilities(net, caps)
    }

    val db =
      Room.inMemoryDatabaseBuilder(context, SmartClipboardDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    try {
      val crypto = KeystoreCryptoManager()
      val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
      val prefs = AppPreferencesRepository(context, crypto, json)
      prefs.clearAllPreferences()
      val clipRepo = ClipRepositoryImpl(db.clipDao(), crypto, SystemClipboardHelper(context))
      val categoryRepo = CategoryRepositoryImpl(db.categoryDao())
      val syncLogRepo = SyncLogRepositoryImpl(db.syncLogDao())

      val crashingProvider =
        object : IntegrationProvider {
          override val providerId = SyncProviderId.TELEGRAM
          override suspend fun isConfigured() = true
          override suspend fun testConnection() = error("boom")
          override suspend fun pushClips(clips: List<ClipItem>) = error("boom")
          override suspend fun pullClips(): ProviderResult<List<PulledClip>> {
            throw IllegalStateException("Unexpected provider crash")
          }
        }

      val engine =
        SyncEngine(context, setOf(crashingProvider), clipRepo, categoryRepo, syncLogRepo, prefs)

      val singleRes = engine.syncSingleProvider(SyncProviderId.TELEGRAM)
      assertTrue(singleRes is ProviderResult.Error)
      assertFalse(engine.state.value.isSyncing)

      engine.syncAll()
      assertFalse(engine.state.value.isSyncing)
    } finally {
      db.close()
    }
  }
}
