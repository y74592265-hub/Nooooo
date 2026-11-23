package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.clipboard.SystemClipboardHelper
import com.example.core.preferences.AppPreferencesRepository
import com.example.core.security.KeystoreCryptoManager
import com.example.data.local.ClipEntity
import com.example.data.local.SmartClipboardDatabase
import com.example.data.repository.CategoryRepositoryImpl
import com.example.data.repository.ClipRepositoryImpl
import com.example.data.repository.SyncQueueRepositoryImpl
import com.example.domain.model.ClipContentType
import com.example.domain.model.ClipSource
import com.example.domain.model.ClipSyncState
import com.example.domain.usecase.SearchFilter
import com.example.domain.usecase.StorageCapacityUseCase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.system.measureTimeMillis

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PerformanceScaleBenchmarkTest {

  private lateinit var context: Context
  private lateinit var db: SmartClipboardDatabase
  private lateinit var crypto: KeystoreCryptoManager
  private lateinit var prefs: AppPreferencesRepository
  private lateinit var clipRepo: ClipRepositoryImpl
  private lateinit var categoryRepo: CategoryRepositoryImpl
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
    syncQueueRepo = SyncQueueRepositoryImpl(db.syncQueueDao())
    categoryRepo.ensureDefaultCategories()
  }

  @After
  fun tearDown() {
    db.close()
  }

  private suspend fun seedClips(count: Int, baseOffset: Int) {
    val clipDao = db.clipDao()
    val baseTs = 1_700_000_000_000L
    for (i in 0 until count) {
      val idx = baseOffset + i
      val isNeedle = idx == 42 || idx == 950 || idx == 4999
      val text =
        if (isNeedle) "Kotlin FTS benchmark needle_$idx for Checkpoint 10 search"
        else "Clipboard payload item number $idx for scalability testing"
      clipDao.insertClip(
        ClipEntity(
          title = "Clip #$idx",
          content = text,
          contentHash = "hash_bench_$idx",
          contentType = if (idx % 3 == 0) ClipContentType.CODE else ClipContentType.TEXT,
          categoryId = ((idx % 5) + 1).toLong(),
          tagsCsv = if (isNeedle) "benchmark,needle" else "scale,item",
          isPinned = idx == 0,
          isFavorite = idx % 10 == 0,
          isSensitive = false,
          isTrashed = false,
          createdAt = baseTs + idx,
          updatedAt = baseTs + idx,
          source = ClipSource.CLIPBOARD,
          syncStatus = ClipSyncState.LOCAL_ONLY,
          contentSizeBytes = text.toByteArray().size.toLong(),
        )
      )
    }
  }

  @Test
  fun `paged SQL queries FTS4 search IME slice and storage aggregation stay fast at 100 1000 and 5000 clips`() =
    runTest {
      // Scale tier 1: 100 clips
      seedClips(100, baseOffset = 0)
      val paged100Ms = measureTimeMillis {
        val page = clipRepo.getActiveClipsPaged(limit = 50, offset = 0)
        assertEquals(50, page.size)
      }

      // Scale tier 2: 1,000 clips total
      seedClips(900, baseOffset = 100)
      val fts1000Ms = measureTimeMillis {
        val matches = clipRepo.searchActiveClipsFts(SearchFilter(query = "needle_950"), limit = 200)
        assertEquals(1, matches.size)
      }

      // Scale tier 3: 5,000 clips total
      seedClips(4000, baseOffset = 1000)

      var paged5000Size = 0
      val paged5000Ms = measureTimeMillis {
        paged5000Size = clipRepo.getActiveClipsPaged(limit = 50, offset = 0).size
      }
      assertEquals(50, paged5000Size)
      assertTrue("Paged 50-row query at 5k clips took ${paged5000Ms}ms", paged5000Ms < 500L)

      var fts5000Size = 0
      val fts5000Ms = measureTimeMillis {
        fts5000Size =
          clipRepo.searchActiveClipsFts(SearchFilter(query = "needle_4999"), limit = 200).size
      }
      assertEquals(1, fts5000Size)
      assertTrue("FTS4 query at 5k clips took ${fts5000Ms}ms", fts5000Ms < 800L)

      // IME Companion recent strip queries only top 8 rows regardless of total vault size
      var imeCount = 0
      val imeSliceMs = measureTimeMillis {
        imeCount = clipRepo.getActiveClipsPaged(limit = 8, offset = 0).size
      }
      assertEquals(8, imeCount)
      assertTrue("IME 8-clip query at 5k clips took ${imeSliceMs}ms", imeSliceMs < 250L)

      // Storage aggregation uses single SQL aggregate row instead of loading 5,000 clips into RAM
      val storageUseCase = StorageCapacityUseCase(context, clipRepo, syncQueueRepo, prefs)
      var totalCount = 0
      val storageMs = measureTimeMillis {
        val stats = storageUseCase.computeStats(largestLimit = 10)
        totalCount = stats.activeClipsCount
        assertEquals(10, stats.largestClips.size)
      }
      assertEquals(5000, totalCount)
      assertTrue("Storage aggregate query at 5k clips took ${storageMs}ms", storageMs < 800L)

      println(
        "BENCHMARK_METRICS: paged_100=${paged100Ms}ms, fts_1000=${fts1000Ms}ms, " +
          "paged_5000=${paged5000Ms}ms, fts_5000=${fts5000Ms}ms, ime_slice_5000=${imeSliceMs}ms, " +
          "storage_stats_5000=${storageMs}ms"
      )
    }
}
