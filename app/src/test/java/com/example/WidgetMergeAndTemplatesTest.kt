package com.example

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.clipboard.SystemClipboardHelper
import com.example.core.security.KeystoreCryptoManager
import com.example.core.widget.ClipboardWidgetProvider
import com.example.data.local.SmartClipboardDatabase
import com.example.data.repository.CategoryRepositoryImpl
import com.example.data.repository.ClipRepositoryImpl
import com.example.domain.intelligence.ClipTemplateEngine
import com.example.domain.model.Category
import com.example.domain.model.CategoryDefaults
import com.example.domain.model.ClipContentType
import com.example.domain.model.ClipItem
import com.example.domain.model.ClipSource
import com.example.ui.components.AddEditClipSheet
import com.example.ui.screens.AllClipsScreen
import com.example.ui.screens.ClipDetailsScreen
import com.example.ui.theme.SmartClipboardTheme
import java.util.Locale
import kotlinx.coroutines.test.runTest
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetMergeAndTemplatesTest {

  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var context: Context
  private lateinit var db: SmartClipboardDatabase
  private lateinit var clipRepo: ClipRepositoryImpl

  @Before
  fun setup() {
    context = ApplicationProvider.getApplicationContext()
    db =
      Room.inMemoryDatabaseBuilder(context, SmartClipboardDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val categoryRepo = CategoryRepositoryImpl(db.categoryDao())
    kotlinx.coroutines.runBlocking { categoryRepo.ensureDefaultCategories() }
    val crypto = KeystoreCryptoManager()
    val helper = SystemClipboardHelper(context)
    clipRepo = ClipRepositoryImpl(db.clipDao(), crypto, helper)
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `template engine detects and expands date time day and uuid deterministically`() {
    assertFalse(ClipTemplateEngine.hasTemplateTokens("plain text without tokens"))
    assertTrue(ClipTemplateEngine.hasTemplateTokens("Report {{date}} {{TIME}}"))

    val fixedUtc = 1_795_776_000_000L // deterministic timestamp
    val expanded =
      ClipTemplateEngine.expand(
        text = "Log [{{date}} {{time}} - {{day}}] ref={{UUID}}",
        nowMillis = fixedUtc,
        locale = Locale.US,
        uuidProvider = { "11111111-2222-4333-8444-555555555555" },
      )

    assertFalse(ClipTemplateEngine.hasTemplateTokens(expanded))
    assertTrue(expanded.contains("ref=11111111-2222-4333-8444-555555555555"))
    assertTrue(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").containsMatchIn(expanded))
  }

  @Test
  fun `widget sql projections strictly exclude sensitive and trashed clips`() = runTest {
    val normal =
      clipRepo.saveNewOrDeduplicate(
        title = "Public Snippet",
        content = "https://kotlinlang.org",
        isSensitive = false,
        isPinned = true,
        source = ClipSource.MANUAL,
      )
    val sensitive =
      clipRepo.saveNewOrDeduplicate(
        title = "Secret Token",
        content = "ghp_superSecretTokenValue1234567890",
        isSensitive = true,
        source = ClipSource.MANUAL,
      )
    val trashed =
      clipRepo.saveNewOrDeduplicate(
        title = "Old Note",
        content = "Should stay hidden in trash",
        isSensitive = false,
        source = ClipSource.MANUAL,
      )
    clipRepo.moveToTrash(listOf(trashed.clipId))

    val widgetCandidates = db.clipDao().getImeCandidateProjections(10)
    assertEquals(1, widgetCandidates.size)
    assertEquals(normal.clipId, widgetCandidates.first().id)

    assertNotNull(db.clipDao().getPasteableContentById(normal.clipId))
    assertNull(db.clipDao().getPasteableContentById(sensitive.clipId))
    assertNull(db.clipDao().getPasteableContentById(trashed.clipId))
  }

  @Test
  fun `widget receiver is registered in manifest`() {
    val widgetManager = AppWidgetManager.getInstance(context)
    assertNotNull(widgetManager)
    val providerComponent = ComponentName(context, ClipboardWidgetProvider::class.java)
    val receiverInfo = context.packageManager.getReceiverInfo(providerComponent, 0)
    assertTrue(receiverInfo.exported)
  }

  @Test
  fun `all clips screen renders merge button when 2+ clips selected and invokes callback`() {
    val categories =
      listOf(Category(CategoryDefaults.PERSONAL_ID, "Personal", 0xFF3B82F6, isDefault = true))
    val clips =
      listOf(
        ClipItem(id = 10L, title = "A", content = "First part", contentHash = "h1"),
        ClipItem(id = 11L, title = "B", content = "Second part", contentHash = "h2"),
      )
    var mergedArgs: Triple<String, Boolean, String>? = null

    composeTestRule.setContent {
      SmartClipboardTheme {
        AllClipsScreen(
          clips = clips,
          categories = categories,
          selectedClipIds = setOf(10L, 11L),
          onToggleSelection = {},
          onSelectAll = {},
          onClearSelection = {},
          onBulkDelete = {},
          onBulkMoveCategory = {},
          onMergeSelected = { sep, del, title -> mergedArgs = Triple(sep, del, title) },
          onOpenClipDetails = {},
          onCopyClip = {},
          onTogglePin = {},
          onToggleFavorite = {},
          onAddNewClip = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("bulk_merge_btn").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("merge_clips_dialog").assertIsDisplayed()
    composeTestRule.onNodeWithTag("merge_sep_chip_1").performClick() // "\n"
    composeTestRule.onNodeWithTag("merge_delete_originals_chk").performClick()
    composeTestRule.onNodeWithTag("merge_confirm_btn").performClick()
    composeTestRule.waitForIdle()

    assertNotNull(mergedArgs)
    assertEquals("\n", mergedArgs?.first)
    assertEquals(true, mergedArgs?.second)
  }

  @Test
  fun `add clip sheet inserts dynamic template token chip into content`() {
    var savedContent = ""
    composeTestRule.setContent {
      SmartClipboardTheme {
        AddEditClipSheet(
          categories =
            listOf(Category(CategoryDefaults.PERSONAL_ID, "Personal", 0xFF3B82F6, isDefault = true)),
          initialContent = "Standup",
          onPasteSystemClipboard = { null },
          onDismiss = {},
          onSave = { _, content, _, _, _, _ -> savedContent = content },
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("insert_token_0").performClick() // + {{date}}
    composeTestRule.onNodeWithTag("save_clip_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()

    assertEquals("Standup {{date}}", savedContent)
  }

  @Test
  fun `clip details screen shows live dynamic template preview and copies filled output`() {
    var copiedSnippet = ""
    val templateClip =
      ClipItem(
        id = 50L,
        title = "Daily Standup",
        content = "Standup Date: {{date}}",
        contentHash = "htpl",
        contentType = ClipContentType.NOTE,
      )

    composeTestRule.setContent {
      SmartClipboardTheme {
        ClipDetailsScreen(
          clip = templateClip,
          categories =
            listOf(Category(CategoryDefaults.NOTES_ID, "Notes", 0xFFF59E0B, isDefault = true)),
          onCopy = {},
          onUpdateClip = {},
          onTogglePin = {},
          onToggleFavorite = {},
          onDelete = {},
          onPushToProvider = { _, _ -> },
          onPasteSystemClipboard = { null },
          onCopySnippetText = { copiedSnippet = it },
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("tool_template_preview").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("Dynamic Template Preview").assertIsDisplayed()
    composeTestRule.onNodeWithTag("tool_copy_template_chip").performClick()
    composeTestRule.waitForIdle()

    assertFalse(copiedSnippet.contains("{{date}}"))
    assertTrue(copiedSnippet.startsWith("Standup Date: "))
  }
}
