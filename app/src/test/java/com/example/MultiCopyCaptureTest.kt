package com.example

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.robolectric.Shadows
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.clipboard.CaptureOutcome
import com.example.core.clipboard.ClipboardCaptureCoordinator
import com.example.core.clipboard.QuickCaptureNotificationHelper
import com.example.core.clipboard.SystemClipboardHelper
import com.example.core.clipboard.SystemClipboardSnapshot
import com.example.core.preferences.AppPreferencesRepository
import com.example.core.preferences.UserSettings
import com.example.core.security.KeystoreCryptoManager
import com.example.core.sync.SyncEngine
import com.example.data.local.SmartClipboardDatabase
import com.example.data.repository.CategoryRepositoryImpl
import com.example.data.repository.ClipRepositoryImpl
import com.example.data.repository.SyncLogRepositoryImpl
import com.example.data.repository.SyncQueueRepositoryImpl
import com.example.domain.model.ClipSource
import com.example.domain.usecase.SaveClipUseCase
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.SmartClipboardTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MultiCopyCaptureTest {

  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var context: Context
  private lateinit var db: SmartClipboardDatabase
  private lateinit var crypto: KeystoreCryptoManager
  private lateinit var prefs: AppPreferencesRepository
  private lateinit var clipboardHelper: SystemClipboardHelper
  private lateinit var clipRepo: ClipRepositoryImpl
  private lateinit var categoryRepo: CategoryRepositoryImpl
  private lateinit var coordinator: ClipboardCaptureCoordinator
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
    clipboardHelper = SystemClipboardHelper(context)
    clipRepo = ClipRepositoryImpl(db.clipDao(), crypto, clipboardHelper)
    categoryRepo = CategoryRepositoryImpl(db.categoryDao())
    categoryRepo.ensureDefaultCategories()
    val syncLogRepo = SyncLogRepositoryImpl(db.syncLogDao())
    val syncQueueRepo = SyncQueueRepositoryImpl(db.syncQueueDao())
    val syncEngine =
      SyncEngine(
        context = context,
        providers = emptySet(),
        clipRepository = clipRepo,
        categoryRepository = categoryRepo,
        syncLogRepository = syncLogRepo,
        syncQueueRepository = syncQueueRepo,
        prefs = prefs,
      )
    coordinator =
      ClipboardCaptureCoordinator(
        clipboardHelper = clipboardHelper,
        clipRepository = clipRepo,
        saveClipUseCase = SaveClipUseCase(clipRepo, categoryRepo, clipboardHelper),
        syncQueueRepository = syncQueueRepo,
        cryptoManager = crypto,
        prefs = prefs,
        syncEngine = syncEngine,
      )
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `sequential multi-copy snapshots save both first and second Arabic names without overwriting`() =
    runTest {
      val first =
        coordinator.captureFromSystemClipboard(
          source = ClipSource.CLIPBOARD,
          overrideSnapshot = SystemClipboardSnapshot(text = "محمد"),
        )
      val second =
        coordinator.captureFromSystemClipboard(
          source = ClipSource.CLIPBOARD,
          overrideSnapshot = SystemClipboardSnapshot(text = "يوسف"),
        )

      assertTrue(first is CaptureOutcome.Saved)
      assertTrue(second is CaptureOutcome.Saved)

      val active = clipRepo.getAllActiveClipsOnce()
      assertEquals(2, active.size)
      assertTrue(active.any { it.content == "محمد" })
      assertTrue(active.any { it.content == "يوسف" })
    }

  @Test
  fun `multi-item ClipData in Android ClipboardManager captures every item without dropping item 1`() =
    runTest {
      val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      val multiClip =
        ClipData.newPlainText("multi", "محمد").apply {
          addItem(ClipData.Item("يوسف"))
          addItem(ClipData.Item("محمد")) // intra-clip duplicate should be deduplicated
        }
      cm.setPrimaryClip(multiClip)

      val snapshots = clipboardHelper.readAllClipboardSnapshots()
      assertEquals(2, snapshots.size)
      assertEquals("محمد", snapshots[0].text)
      assertEquals("يوسف", snapshots[1].text)

      val outcomes = coordinator.captureAllFromSystemClipboard(source = ClipSource.CLIPBOARD)
      val saved = outcomes.filterIsInstance<CaptureOutcome.Saved>()
      assertEquals(2, saved.size)

      val active = clipRepo.getAllActiveClipsOnce()
      assertEquals(2, active.size)
      assertTrue(active.any { it.content == "محمد" })
      assertTrue(active.any { it.content == "يوسف" })
    }

  @Test
  fun `concurrent IME snapshot captures preserve both items safely under mutex`() = runTest {
    val jobs =
      listOf(
        async {
          coordinator.captureAllFromSystemClipboard(
            source = ClipSource.IME,
            overrideSnapshots = listOf(SystemClipboardSnapshot("محمد")),
            allowWhenAutoCaptureDisabled = true,
          )
        },
        async {
          coordinator.captureAllFromSystemClipboard(
            source = ClipSource.IME,
            overrideSnapshots = listOf(SystemClipboardSnapshot("يوسف")),
            allowWhenAutoCaptureDisabled = true,
          )
        },
      )
    jobs.awaitAll()

    val active = clipRepo.getAllActiveClipsOnce()
    assertEquals(2, active.size)
    assertTrue(active.any { it.content == "محمد" })
    assertTrue(active.any { it.content == "يوسف" })
  }

  @Test
  fun `direct text selection capture saves snippet even when foreground auto-capture is disabled`() =
    runTest {
      prefs.updateAutoCapture(false)
      prefs.updateInstantAutoSave(false)

      val outcome =
        coordinator.captureSnippetDirectly(
          rawText = "محمد",
          source = ClipSource.SHARE_SHEET,
        )
      assertTrue(outcome is CaptureOutcome.Saved)
      assertEquals("محمد", clipRepo.getAllActiveClipsOnce().single().content)
    }

  @Test
  fun `home multi-copy guide card renders and invokes callbacks`() {
    var addTileClicked = false
    var dismissed = false

    composeTestRule.setContent {
      SmartClipboardTheme {
        HomeScreen(
          clips = emptyList(),
          categories = emptyList(),
          autoCapturedBanner = null,
          onCaptureClipboardNow = {},
          onUndoAutoCaptured = {},
          onMarkBannerSensitive = {},
          onDismissAutoCapturedBanner = {},
          onAddNewClip = {},
          onOpenClipDetails = {},
          onCopyClip = {},
          onTogglePin = {},
          onToggleFavorite = {},
          onOpenFavorites = {},
          onOpenIntegrations = {},
          onOpenTrash = {},
          onOpenSettings = {},
          onOpenBackupSync = {},
          showMultiCopyGuide = true,
          onAddQuickCaptureTile = { addTileClicked = true },
          onDismissMultiCopyGuide = { dismissed = true },
        )
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("multicopy_guide_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("home_add_qs_tile_btn").performClick()
    composeTestRule.onNodeWithTag("dismiss_multicopy_guide_btn").performClick()
    assertTrue(addTileClicked)
    assertTrue(dismissed)
  }

  @Test
  fun `home guide card quick capture notification button invokes toggle callback`() {
    Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    var notifToggled: Boolean? = null

    composeTestRule.setContent {
      SmartClipboardTheme {
        HomeScreen(
          clips = emptyList(),
          categories = emptyList(),
          autoCapturedBanner = null,
          onCaptureClipboardNow = {},
          onUndoAutoCaptured = {},
          onMarkBannerSensitive = {},
          onDismissAutoCapturedBanner = {},
          onAddNewClip = {},
          onOpenClipDetails = {},
          onCopyClip = {},
          onTogglePin = {},
          onToggleFavorite = {},
          onOpenFavorites = {},
          onOpenIntegrations = {},
          onOpenTrash = {},
          onOpenSettings = {},
          onOpenBackupSync = {},
          showMultiCopyGuide = true,
          quickCaptureNotificationEnabled = false,
          onToggleQuickCaptureNotification = { notifToggled = it },
        )
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("home_toggle_notif_btn").assertIsDisplayed().performClick()
    assertEquals(true, notifToggled)
  }

  @Test
  fun `settings quick tile and notification switches render cleanly`() {
    Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    var notifToggled: Boolean? = null
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
          onQuickCaptureNotificationToggle = { notifToggled = it },
        )
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("add_qs_tile_btn").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("quick_capture_notif_switch").performScrollTo().assertIsDisplayed().performClick()
    assertEquals(true, notifToggled)
  }

  @Test
  fun `quick capture notification preference persists and helper runs safely`() = runTest {
    prefs.updateQuickCaptureNotificationEnabled(true)
    prefs.updateLastDetectedClipHash("abc123")
    assertEquals("abc123", prefs.getLastDetectedClipHashOnce())

    QuickCaptureNotificationHelper.showOrCancel(context, enabled = true)
    QuickCaptureNotificationHelper.showOrCancel(context, enabled = false)
  }
}
