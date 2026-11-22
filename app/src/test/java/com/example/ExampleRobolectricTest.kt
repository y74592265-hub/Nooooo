package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.clipboard.SystemClipboardHelper
import com.example.core.security.KeystoreCryptoManager
import com.example.data.local.SmartClipboardDatabase
import com.example.data.repository.CategoryRepositoryImpl
import com.example.data.repository.ClipRepositoryImpl
import com.example.domain.model.ClipContentType
import com.example.domain.model.ClipSource
import kotlinx.coroutines.test.runTest
import com.example.core.preferences.AppLanguage
import com.example.core.preferences.AppPreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `app name matches Smart Clipboard`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Smart Clipboard", appName)
  }

  @Test
  fun `default language on first launch is Arabic`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs =
      AppPreferencesRepository(
        context,
        KeystoreCryptoManager(),
        Json { ignoreUnknownKeys = true; encodeDefaults = true },
      )
    prefs.clearAllPreferences()
    val settings = prefs.userSettings.first()
    assertEquals(AppLanguage.ARABIC, settings.language)
  }

  @Test
  fun `clips persist across database reopen simulating app restart`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val dbName = "restart_persistence_test_${System.currentTimeMillis()}.db"
    val crypto = KeystoreCryptoManager()
    val clipboardHelper = SystemClipboardHelper(context)

    // Session 1: Create DB, save clip, close DB
    val db1 =
      Room.databaseBuilder(context, SmartClipboardDatabase::class.java, dbName)
        .allowMainThreadQueries()
        .build()
    val catRepo1 = CategoryRepositoryImpl(db1.categoryDao())
    val clipRepo1 = ClipRepositoryImpl(db1.clipDao(), crypto, clipboardHelper)
    catRepo1.ensureDefaultCategories()
    val saved =
      clipRepo1.saveNewOrDeduplicate(
        title = "API Endpoint",
        content = "https://api.example.com/v2/clips",
        tags = listOf("backend", "rest"),
        isSensitive = true,
        source = ClipSource.MANUAL,
      )
    db1.close()

    // Session 2: Reopen DB and verify clip + decrypted content + auto-classification survived
    val db2 =
      Room.databaseBuilder(context, SmartClipboardDatabase::class.java, dbName)
        .allowMainThreadQueries()
        .build()
    val clipRepo2 = ClipRepositoryImpl(db2.clipDao(), crypto, clipboardHelper)
    val restored = clipRepo2.getClipById(saved.clipId)
    assertNotNull(restored)
    assertEquals("API Endpoint", restored!!.title)
    assertEquals("https://api.example.com/v2/clips", restored.content)
    assertEquals(ClipContentType.LINK, restored.contentType)
    assertTrue(restored.isSensitive)
    db2.close()
    context.deleteDatabase(dbName)
  }
}
