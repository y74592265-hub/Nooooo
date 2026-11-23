package com.example

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.example.core.preferences.AppLanguage
import com.example.core.preferences.ThemeMode
import com.example.core.preferences.UserSettings
import com.example.domain.model.Category
import com.example.domain.model.CategoryDefaults
import com.example.domain.model.ClipContentType
import com.example.domain.model.ClipItem
import com.example.domain.usecase.SearchFilter
import com.example.ui.components.PlatformSafeTextToolbar
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.SmartClipboardTheme
import com.example.ui.viewmodel.AutoCapturedBannerState
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmartClipboardUiTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val sampleCategories =
    listOf(
      Category(CategoryDefaults.PERSONAL_ID, "Personal", 0xFF3B82F6, isDefault = true),
      Category(CategoryDefaults.PROGRAMMING_ID, "Programming", 0xFF06B6D4, isDefault = true),
    )

  private fun arabicContext(): Context {
    val base = ApplicationProvider.getApplicationContext<Context>()
    val locale = Locale.forLanguageTag("ar")
    val config = Configuration(base.resources.configuration)
    config.setLocale(locale)
    config.setLayoutDirection(locale)
    return base.createConfigurationContext(config)
  }

  @Test
  fun `home screen displays auto captured banner with undo and triggers copy action`() {
    var copiedClip: ClipItem? = null
    var undone = false
    val clip =
      ClipItem(
        id = 1L,
        title = "Compose Tip",
        content = "val state by flow.collectAsStateWithLifecycle()",
        contentHash = "abc",
        contentType = ClipContentType.CODE,
        categoryId = CategoryDefaults.PROGRAMMING_ID,
      )
    val banner =
      AutoCapturedBannerState(
        clip = clip.copy(id = 99L, content = "https://developer.android.com"),
        autoMarkedSensitive = false,
        sensitiveReason = null,
        queuedProviders = emptyList(),
      )

    composeTestRule.setContent {
      SmartClipboardTheme {
        HomeScreen(
          clips = listOf(clip),
          totalActiveCount = 1,
          categories = sampleCategories,
          autoCapturedBanner = banner,
          onCaptureClipboardNow = {},
          onUndoAutoCaptured = { undone = true },
          onMarkBannerSensitive = {},
          onDismissAutoCapturedBanner = {},
          onAddNewClip = {},
          onOpenClipDetails = {},
          onCopyClip = { copiedClip = it },
          onTogglePin = {},
          onToggleFavorite = {},
          onOpenFavorites = {},
          onOpenIntegrations = {},
          onOpenTrash = {},
          onOpenSettings = {},
          onOpenBackupSync = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("auto_captured_banner").assertIsDisplayed()
    composeTestRule.onNodeWithTag("undo_auto_captured_btn").performClick()
    assertTrue(undone)

    composeTestRule.onNodeWithTag("copy_button_1").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertEquals(1L, copiedClip?.id)
  }

  @Test
  fun `search screen updates query as user types`() {
    var latestQuery = ""
    composeTestRule.setContent {
      SmartClipboardTheme {
        SearchScreen(
          filter = SearchFilter(query = ""),
          results = emptyList(),
          categories = sampleCategories,
          onQueryChange = { latestQuery = it },
          onCategoryChange = {},
          onTypeChange = {},
          onDateFilterChange = {},
          onSortOrderChange = {},
          onOpenClipDetails = {},
          onCopyClip = {},
          onTogglePin = {},
          onToggleFavorite = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("search_input").performTextInput("kotlin")
    assertEquals("kotlin", latestQuery)
    composeTestRule.onNodeWithText("0 results (offline)").assertIsDisplayed()
  }

  @Test
  fun `arabic rtl dark theme renders settings and home screens cleanly`() {
    val arCtx = arabicContext()
    var selectedLang = AppLanguage.ARABIC
    composeTestRule.setContent {
      CompositionLocalProvider(
        LocalContext provides arCtx,
        LocalLayoutDirection provides LayoutDirection.Rtl,
      ) {
        SmartClipboardTheme(themeMode = ThemeMode.DARK) {
          SettingsScreen(
            settings = UserSettings(themeMode = ThemeMode.DARK, language = AppLanguage.ARABIC),
            onThemeChange = {},
            onLanguageChange = { selectedLang = it },
            onAppLockToggle = {},
            onAutoCaptureToggle = {},
            onRetentionDaysChange = {},
            onClearSystemClipboard = {},
            onWipeAllData = {},
          )
        }
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("المظهر واللغة").assertIsDisplayed()
    composeTestRule.onNodeWithTag("lang_english").performScrollTo().performClick()
    assertEquals(AppLanguage.ENGLISH, selectedLang)
  }

  @Test
  fun `home screen renders arabic rtl vault stats and localized category in light theme`() {
    val arCtx = arabicContext()
    val clip =
      ClipItem(
        id = 7L,
        title = "",
        content = "مرحبا بكم في الحافظة الذكية",
        contentHash = "hash7",
        contentType = ClipContentType.TEXT,
        categoryId = CategoryDefaults.WORK_ID,
      )

    composeTestRule.setContent {
      CompositionLocalProvider(
        LocalContext provides arCtx,
        LocalLayoutDirection provides LayoutDirection.Rtl,
      ) {
        SmartClipboardTheme(themeMode = ThemeMode.LIGHT) {
          HomeScreen(
            clips = listOf(clip),
            categories = listOf(Category(CategoryDefaults.WORK_ID, "Work", 0xFF8B5CF6, isDefault = true)),
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
          )
        }
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("أحدث النصوص").assertIsDisplayed()
    composeTestRule.onNodeWithText("العمل").assertIsDisplayed()
    composeTestRule.onNodeWithText("مرحبا بكم في الحافظة الذكية").assertIsDisplayed()
  }

  @Test
  fun `safe text toolbar ignores detached views and never double finishes floating action mode`() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    var finishCount = 0
    var activeCallback: android.view.ActionMode.Callback2? = null

    val fakeMode =
      object : android.view.ActionMode() {
        override fun setTitle(title: CharSequence?) = Unit
        override fun setTitle(resId: Int) = Unit
        override fun setSubtitle(subtitle: CharSequence?) = Unit
        override fun setSubtitle(resId: Int) = Unit
        override fun setCustomView(view: android.view.View?) = Unit
        override fun invalidate() = Unit
        override fun finish() {
          finishCount++
          activeCallback?.onDestroyActionMode(this)
        }
        override fun getMenu(): android.view.Menu? = null
        override fun getTitle(): CharSequence? = null
        override fun getSubtitle(): CharSequence? = null
        override fun getCustomView(): android.view.View? = null
        override fun getMenuInflater(): android.view.MenuInflater? = null
      }

    // Detached view should safely skip startActionMode without crashing or leaking
    val detachedView = android.view.View(ctx)
    val detachedToolbar = PlatformSafeTextToolbar(detachedView)
    detachedToolbar.showMenu(Rect(0f, 0f, 10f, 10f), onCopyRequested = {}, null, null, null)
    assertEquals(TextToolbarStatus.Hidden, detachedToolbar.status)

    // Attached view with re-entrant hide() inside onCopyRequested finishes ActionMode only ONCE
    val attachedView =
      object : android.view.View(ctx) {
        override fun isAttachedToWindow(): Boolean = true
        override fun getWindowToken(): android.os.IBinder = android.os.Binder()
        override fun startActionMode(callback: android.view.ActionMode.Callback?, type: Int): android.view.ActionMode {
          activeCallback = callback as android.view.ActionMode.Callback2
          return fakeMode
        }
      }

    val toolbar = PlatformSafeTextToolbar(attachedView)
    var copied = false
    toolbar.showMenu(
      rect = Rect(0f, 0f, 100f, 40f),
      onCopyRequested = {
        copied = true
        toolbar.hide()
      },
      onPasteRequested = null,
      onCutRequested = null,
      onSelectAllRequested = null,
    )
    assertEquals(TextToolbarStatus.Shown, toolbar.status)

    val dummyMenu = android.widget.PopupMenu(ctx, attachedView).menu
    activeCallback?.onCreateActionMode(fakeMode, dummyMenu)
    val copyItem = dummyMenu.getItem(0)
    activeCallback?.onActionItemClicked(fakeMode, copyItem)
    toolbar.hide()

    assertTrue(copied)
    assertEquals(1, finishCount)
    assertEquals(TextToolbarStatus.Hidden, toolbar.status)
  }
}
