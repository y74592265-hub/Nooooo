package com.example

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
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
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.SmartClipboardTheme
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
  fun `home screen displays detected clipboard banner and triggers copy action`() {
    var copiedClip: ClipItem? = null
    var savedDetected = false
    val clip =
      ClipItem(
        id = 1L,
        title = "Compose Tip",
        content = "val state by flow.collectAsStateWithLifecycle()",
        contentHash = "abc",
        contentType = ClipContentType.CODE,
        categoryId = CategoryDefaults.PROGRAMMING_ID,
      )

    composeTestRule.setContent {
      SmartClipboardTheme {
        HomeScreen(
          clips = listOf(clip),
          categories = sampleCategories,
          detectedClipboardText = "https://developer.android.com",
          onCaptureClipboardNow = {},
          onSaveDetectedClipboard = { savedDetected = true },
          onDismissDetectedClipboard = {},
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
    composeTestRule.onNodeWithTag("detected_clipboard_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("save_detected_button").performClick()
    assertTrue(savedDetected)

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
            detectedClipboardText = null,
            onCaptureClipboardNow = {},
            onSaveDetectedClipboard = {},
            onDismissDetectedClipboard = {},
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
}
