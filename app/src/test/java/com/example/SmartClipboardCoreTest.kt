package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.clipboard.SystemClipboardHelper
import com.example.core.security.KeystoreCryptoManager
import com.example.data.local.SmartClipboardDatabase
import com.example.data.repository.CategoryRepositoryImpl
import com.example.data.repository.ClipRepositoryImpl
import com.example.domain.model.CategoryDefaults
import com.example.domain.model.ClipContentType
import com.example.domain.model.ClipSource
import com.example.domain.usecase.DateFilter
import com.example.domain.usecase.ExportImportUseCase
import com.example.domain.usecase.SaveClipUseCase
import com.example.domain.usecase.SearchFilter
import com.example.domain.usecase.SearchSortOrder
import com.example.domain.usecase.SmartSearchUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmartClipboardCoreTest {

  private lateinit var db: SmartClipboardDatabase
  private lateinit var crypto: KeystoreCryptoManager
  private lateinit var clipboardHelper: SystemClipboardHelper
  private lateinit var clipRepo: ClipRepositoryImpl
  private lateinit var categoryRepo: CategoryRepositoryImpl
  private lateinit var saveClipUseCase: SaveClipUseCase
  private lateinit var searchUseCase: SmartSearchUseCase
  private lateinit var exportImportUseCase: ExportImportUseCase
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  @Before
  fun setUp() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, SmartClipboardDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    crypto = KeystoreCryptoManager()
    clipboardHelper = SystemClipboardHelper(context)
    clipRepo = ClipRepositoryImpl(db.clipDao(), crypto, clipboardHelper)
    categoryRepo = CategoryRepositoryImpl(db.categoryDao())
    categoryRepo.ensureDefaultCategories()
    saveClipUseCase = SaveClipUseCase(clipRepo, categoryRepo, clipboardHelper)
    searchUseCase = SmartSearchUseCase()
    exportImportUseCase = ExportImportUseCase(clipRepo, categoryRepo, crypto, json)
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `add edit soft-delete restore and permanent delete clip lifecycle`() = runTest {
    val res = saveClipUseCase("Meeting Notes", "Discuss Q4 roadmap", tags = listOf("work"))!!
    assertFalse(res.wasDuplicateMerged)

    val saved = clipRepo.getClipById(res.clipId)!!
    assertEquals("Discuss Q4 roadmap", saved.content)

    // Edit
    clipRepo.updateClip(saved.copy(content = "Discuss Q4 roadmap & budget", isFavorite = true))
    val edited = clipRepo.getClipById(res.clipId)!!
    assertEquals("Discuss Q4 roadmap & budget", edited.content)
    assertTrue(edited.isFavorite)

    // Soft delete to Trash
    clipRepo.moveToTrash(listOf(edited.id))
    assertEquals(0, clipRepo.observeActiveClips().first().size)
    assertEquals(1, clipRepo.observeTrashedClips().first().size)

    // Restore
    clipRepo.restoreFromTrash(listOf(edited.id))
    assertEquals(1, clipRepo.observeActiveClips().first().size)
    assertEquals(0, clipRepo.observeTrashedClips().first().size)

    // Permanent delete
    clipRepo.deleteForever(listOf(edited.id))
    assertEquals(0, clipRepo.observeActiveClips().first().size)
  }

  @Test
  fun `duplicate detection merges tags and avoids duplicate entries`() = runTest {
    val first = saveClipUseCase("Link 1", "https://kotlinlang.org", tags = listOf("kotlin"))!!
    val second = saveClipUseCase("", "  https://kotlinlang.org  ", tags = listOf("docs"), isPinned = true)!!

    assertFalse(first.wasDuplicateMerged)
    assertTrue(second.wasDuplicateMerged)
    assertEquals(first.clipId, second.clipId)

    val merged = clipRepo.getClipById(first.clipId)!!
    assertEquals(listOf("kotlin", "docs"), merged.tags)
    assertTrue(merged.isPinned)
    assertEquals(1, clipRepo.getAllActiveClipsOnce().size)
  }

  @Test
  fun `categories creation rename move and deletion reassigns clips to Personal`() = runTest {
    val customId = categoryRepo.createCategory("Snippets", 0xFFEC4899)
    val saved = saveClipUseCase("Code", "fun hello() = Unit", categoryId = customId)!!

    categoryRepo.renameCategory(customId, "Kotlin Snippets", 0xFF06B6D4)
    val cats = categoryRepo.getAllCategoriesOnce()
    assertTrue(cats.any { it.id == customId && it.name == "Kotlin Snippets" })

    // Deleting custom folder preserves clip by reassigning to Personal
    categoryRepo.deleteCustomCategory(customId)
    val clipAfterDelete = clipRepo.getClipById(saved.clipId)!!
    assertEquals(CategoryDefaults.PERSONAL_ID, clipAfterDelete.categoryId)
  }

  @Test
  fun `offline smart search filters by query category type date and ranks by relevance`() = runTest {
    saveClipUseCase("Compose Guide", "https://developer.android.com/compose", tags = listOf("ui"))
    saveClipUseCase("SQL Snippet", "SELECT * FROM clips WHERE isPinned = 1;", tags = listOf("db"))
    saveClipUseCase("Grocery List", "Milk, Coffee, Dates", tags = listOf("home"))

    val all = clipRepo.getAllActiveClipsOnce()
    val codeResults =
      searchUseCase(all, SearchFilter(query = "clips", contentType = ClipContentType.CODE))
    assertEquals(1, codeResults.size)
    assertEquals("SQL Snippet", codeResults.first().title)

    val linkResults =
      searchUseCase(
        all,
        SearchFilter(query = "compose", dateFilter = DateFilter.TODAY, sortOrder = SearchSortOrder.RELEVANCE),
      )
    assertEquals(1, linkResults.size)
    assertEquals("Compose Guide", linkResults.first().title)
  }

  @Test
  fun `encryption and encrypted backup restore with integrity check`() = runTest {
    // Local encryption check
    val secretText = "sk_test_sensitive_clip_9911"
    val encryptedLocal = crypto.encryptLocal(secretText)
    assertNotEquals(secretText, encryptedLocal)
    assertEquals(secretText, crypto.decryptLocal(encryptedLocal))

    // Save both normal and sensitive clips, export encrypted .scbak, wipe, and restore
    saveClipUseCase("Token", secretText, isSensitive = true, tags = listOf("sec"))
    saveClipUseCase("Public Note", "Hello Smart Clipboard", tags = listOf("general"))

    val encryptedBackup = exportImportUseCase.exportEncryptedBackup("MyStrongPass123!")
    assertTrue(encryptedBackup.contains("SMART_CLIPBOARD_ENC_V1"))
    assertFalse(encryptedBackup.contains(secretText))

    // Wipe DB completely
    clipRepo.wipeAllData()
    assertEquals(0, clipRepo.getAllActiveClipsOnce().size)

    // Wrong passphrase must fail
    try {
      exportImportUseCase.restoreFromRawContent(encryptedBackup, "WrongPass")
      fail("Expected decryption to fail with wrong passphrase")
    } catch (_: Exception) {
      // Expected
    }

    // Correct passphrase restores everything with checksum verified
    val summary = exportImportUseCase.restoreFromRawContent(encryptedBackup, "MyStrongPass123!")
    assertEquals(2, summary.importedCount)
    assertTrue(summary.checksumVerified)
    val restored = clipRepo.getAllActiveClipsOnce()
    assertTrue(restored.any { it.content == secretText && it.isSensitive })
  }

  @Test
  fun `tampered json backup fails SHA-256 integrity verification`() = runTest {
    saveClipUseCase("Original", "Trusted content")
    val jsonExport = exportImportUseCase.exportAsJson()
    val tampered = jsonExport.replace("Trusted content", "Tampered content")

    try {
      exportImportUseCase.restoreFromRawContent(tampered, null)
      fail("Expected SHA-256 integrity verification error")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message?.contains("integrity", ignoreCase = true) == true)
    }
  }
}
