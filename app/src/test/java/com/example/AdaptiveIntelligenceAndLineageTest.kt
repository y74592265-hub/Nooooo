package com.example

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.clipboard.SystemClipboardHelper
import com.example.core.ime.ContextDetectionEngine
import com.example.core.preferences.AppPreferencesRepository
import com.example.core.preferences.UserSettings
import com.example.core.security.IntelligencePrivacyGate
import com.example.core.security.KeystoreCryptoManager
import com.example.data.local.SmartClipboardDatabase
import com.example.data.repository.CategoryRepositoryImpl
import com.example.data.repository.ClipLineageRepositoryImpl
import com.example.data.repository.ClipRepositoryImpl
import com.example.domain.intelligence.ArabicTextNormalizer
import com.example.domain.intelligence.ClipPredictionEngine
import com.example.domain.intelligence.ClipSmartTools
import com.example.domain.intelligence.UsageLearningEngine
import com.example.domain.lineage.ClipLineageEngine
import com.example.domain.lineage.LineDiffEngine
import com.example.domain.model.ClipContentType
import com.example.domain.model.ClipTransitionEdge
import com.example.domain.model.DiffLineType
import com.example.domain.model.FieldContextSignal
import com.example.domain.model.FieldIntent
import com.example.domain.model.ImeClipCandidate
import com.example.domain.model.PredictionReason
import com.example.domain.usecase.SaveClipUseCase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AdaptiveIntelligenceAndLineageTest {

  private lateinit var context: Context
  private lateinit var db: SmartClipboardDatabase
  private lateinit var crypto: KeystoreCryptoManager
  private lateinit var prefs: AppPreferencesRepository
  private lateinit var clipboardHelper: SystemClipboardHelper
  private lateinit var clipRepo: ClipRepositoryImpl
  private lateinit var categoryRepo: CategoryRepositoryImpl
  private lateinit var lineageRepo: ClipLineageRepositoryImpl
  private lateinit var usageEngine: UsageLearningEngine
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
    lineageRepo =
      ClipLineageRepositoryImpl(
        database = db,
        clipDao = db.clipDao(),
        clipRevisionDao = db.clipRevisionDao(),
        cryptoManager = crypto,
        clipboardHelper = clipboardHelper,
        lineageEngine = ClipLineageEngine(),
      )
    usageEngine = UsageLearningEngine(db.clipDao(), db.clipTransitionDao(), prefs)
    categoryRepo.ensureDefaultCategories()
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `context detection engine classifies URL email phone multiline and password shield flags`() {
    val urlInfo =
      EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        packageName = "com.android.chrome"
      }
    val urlSignal = ContextDetectionEngine.analyze(urlInfo)
    assertEquals(FieldIntent.URL, urlSignal.intent)
    assertFalse(urlSignal.isSecretField)

    val emailInfo =
      EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
      }
    assertEquals(FieldIntent.EMAIL, ContextDetectionEngine.analyze(emailInfo).intent)

    val phoneInfo = EditorInfo().apply { inputType = InputType.TYPE_CLASS_PHONE }
    assertEquals(FieldIntent.PHONE_OR_NUMBER, ContextDetectionEngine.analyze(phoneInfo).intent)

    val pwdInfo =
      EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
      }
    val pwdSignal = ContextDetectionEngine.analyze(pwdInfo)
    assertTrue(pwdSignal.isSecretField)
    assertTrue(pwdSignal.noPersonalizedLearning)

    val decision = IntelligencePrivacyGate.evaluateImeField(pwdSignal, UserSettings())
    assertFalse(decision.canShowImeChips)
    assertFalse(decision.canCaptureFromIme)
    assertFalse(decision.canLearnUsage)
    assertFalse(decision.canGroupLineage)
  }

  @Test
  fun `SQL IME projection excludes sensitive clips and UsageLearningEngine never records secret fields`() =
    runTest {
      val pub1 = clipRepo.saveNewOrDeduplicate("Link", "https://example.com/docs", isSensitive = false).clipId
      val pub2 = clipRepo.saveNewOrDeduplicate("Email", "dev@example.com", isSensitive = false).clipId
      val secretId =
        clipRepo.saveNewOrDeduplicate("Secret", "SuperSecretToken_12345", isSensitive = true).clipId

      // SQL projection must strictly omit sensitive rows
      val imeRows = db.clipDao().getImeCandidateProjections(limit = 20)
      assertEquals(2, imeRows.size)
      assertFalse(imeRows.any { it.id == secretId })
      assertFalse(imeRows.any { it.preview.contains("SuperSecretToken") })

      // Recording sensitive clip or secret field must leave zero transitions & 0 usage count
      val secretSignal = FieldContextSignal(isSecretField = true)
      usageEngine.recordUsageAndProcess(secretId, isSensitive = true, nowMs = 10_000L)
      usageEngine.recordUsageAndProcess(pub1, isSensitive = false, signal = secretSignal, nowMs = 12_000L)
      assertEquals(0, db.clipDao().getClipById(secretId)!!.useCount)
      assertEquals(0, db.clipDao().getClipById(pub1)!!.useCount)

      // Co-usage within 120s records transition pub1 -> pub2
      usageEngine.recordUsageAndProcess(pub1, isSensitive = false, nowMs = 20_000L)
      usageEngine.recordUsageAndProcess(pub2, isSensitive = false, nowMs = 45_000L)
      val transitions = db.clipTransitionDao().getTopTransitionsFrom(pub1)
      assertEquals(1, transitions.size)
      assertEquals(pub2, transitions.first().toClipId)

      // Clearing learning data wipes both transitions and useCount
      usageEngine.clearAllLearningData()
      assertTrue(db.clipTransitionDao().getTopTransitionsFrom(pub1).isEmpty())
      assertEquals(0, db.clipDao().getClipById(pub1)!!.useCount)
      assertEquals(0, db.clipDao().getClipById(pub2)!!.useCount)
    }

  @Test
  fun `prediction engine promotes field match and conservative next clip edge with threshold`() {
    val now = 1_000_000L
    val candidates =
      listOf(
        ImeClipCandidate(1L, "Random", "hello world", ClipContentType.TEXT, 1L, false, false, 0, null, now, 1),
        ImeClipCandidate(2L, "Email", "ops@company.io", ClipContentType.TEXT, 1L, false, false, 2, null, now - 5000, 1),
        ImeClipCandidate(3L, "Docs", "https://kotlinlang.org", ClipContentType.LINK, 4L, false, false, 1, null, now - 8000, 1),
      )

    val urlRanked =
      ClipPredictionEngine.rank(
        candidates = candidates,
        signal = FieldContextSignal(intent = FieldIntent.URL),
        lastUsedClipId = null,
        transitionsFromLast = emptyList(),
        settings = UserSettings(),
        nowMs = now,
      )
    assertEquals(3L, urlRanked.first().candidate.id)
    assertEquals(PredictionReason.MATCHES_FIELD, urlRanked.first().reason)

    // Single hit edge should NOT promote NEXT_IN_SEQUENCE yet (conservative hitCount >= 2)
    val weakNext =
      ClipPredictionEngine.rank(
        candidates = candidates,
        signal = FieldContextSignal(intent = FieldIntent.GENERAL),
        lastUsedClipId = 1L,
        transitionsFromLast = listOf(ClipTransitionEdge(1L, 2L, weight = 1.0f, hitCount = 1, lastTriggeredAt = now)),
        settings = UserSettings(),
        nowMs = now,
      )
    assertFalse(weakNext.any { it.reason == PredictionReason.NEXT_IN_SEQUENCE })

    // Strong edge (hitCount >= 2 && weight >= 1.5) promotes NEXT_IN_SEQUENCE first
    val strongNext =
      ClipPredictionEngine.rank(
        candidates = candidates,
        signal = FieldContextSignal(intent = FieldIntent.GENERAL),
        lastUsedClipId = 1L,
        transitionsFromLast = listOf(ClipTransitionEdge(1L, 2L, weight = 2.4f, hitCount = 3, lastTriggeredAt = now)),
        settings = UserSettings(),
        nowMs = now,
      )
    assertEquals(2L, strongNext.first().candidate.id)
    assertEquals(PredictionReason.NEXT_IN_SEQUENCE, strongNext.first().reason)
  }

  @Test
  fun `smart lineage stacks near duplicate drafts restores revisions and splits standalone clips`() =
    runTest {
      val saveUseCase =
        SaveClipUseCase(
          clipRepository = clipRepo,
          categoryRepository = categoryRepo,
          clipboardHelper = clipboardHelper,
          lineageRepository = lineageRepo,
          preferencesRepository = prefs,
          cryptoManager = crypto,
        )

      val draftV1 =
        "Dear hiring manager, I am writing to apply for the Senior Android Engineer role on your mobile platform team."
      val draftV2 =
        "Dear hiring manager, I am writing to apply for the Staff Android Engineer role on your mobile platform team!"

      val res1 = saveUseCase(title = "Cover Letter", content = draftV1)!!
      assertFalse(res1.wasLineageVersioned)

      val res2 = saveUseCase(title = "Cover Letter", content = draftV2)!!
      assertTrue(res2.wasLineageVersioned)
      assertEquals(res1.clipId, res2.clipId)
      assertEquals(2, res2.newVersionNumber)

      // Links must NEVER merge via lineage even if very similar
      val link1 = saveUseCase(title = "", content = "https://example.com/articles/android-performance-part-1-deep-dive-2026")!!
      val link2 = saveUseCase(title = "", content = "https://example.com/articles/android-performance-part-2-deep-dive-2026")!!
      assertFalse(link2.wasLineageVersioned)
      assertTrue(link1.clipId != link2.clipId)

      val revisions = lineageRepo.getRevisionsOnce(res1.clipId)
      assertEquals(1, revisions.size)
      assertEquals(1, revisions.first().versionNumber)
      assertEquals(draftV1, revisions.first().content)

      // Diff works cleanly
      val diff = LineDiffEngine.diff(draftV1, draftV2)
      assertEquals(1, diff.removedCount)
      assertEquals(1, diff.addedCount)
      assertTrue(diff.lines.any { it.type == DiffLineType.REMOVED })
      assertTrue(diff.lines.any { it.type == DiffLineType.ADDED })

      // Restore v1 -> current becomes draftV1, v2 archived as revision
      assertTrue(lineageRepo.restoreRevision(res1.clipId, revisions.first().id))
      assertEquals(draftV1, clipRepo.getClipById(res1.clipId)!!.content)

      // Split revision -> creates independent clip
      val revAfterRestore = lineageRepo.getRevisionsOnce(res1.clipId)
      assertEquals(1, revAfterRestore.size)
      val splitId = lineageRepo.splitRevisionAsStandalone(res1.clipId, revAfterRestore.first().id)
      assertNotNull(splitId)
      assertEquals(draftV2, clipRepo.getClipById(splitId!!)!!.content)
      assertTrue(lineageRepo.getRevisionsOnce(res1.clipId).isEmpty())
    }

  @Test
  fun `arabic normalizer and smart tools clean urls format json decode base64 and extract entities`() {
    assertTrue(ArabicTextNormalizer.normalizedContains("شركة أحمد للإلكترونيات", "احمد"))
    assertTrue(ArabicTextNormalizer.normalizedContains("فَاتُورَةُ الشِّرَاءِ", "فاتوره"))
    assertTrue(ArabicTextNormalizer.normalizedContains("getUserClipHistory", "clip history"))

    val dirtyUrl = "https://shop.example.com/item?id=42&utm_source=tg&fbclid=XYZ123"
    assertEquals("https://shop.example.com/item?id=42", ClipSmartTools.stripTrackingParamsIfChanged(dirtyUrl))
    assertNull(ClipSmartTools.stripTrackingParamsIfChanged("https://shop.example.com/item?id=42"))

    val rawJson = """{"a":1,"b":true}"""
    val pretty = ClipSmartTools.prettyJsonOrNull(rawJson)
    assertNotNull(pretty)
    assertEquals("""{"a":1,"b":true}""", ClipSmartTools.minifyJsonOrNull(pretty!!))

    assertEquals("0501234567", ClipSmartTools.toWesternDigits("٠٥٠١٢٣٤٥٦٧"))

    val entities =
      ClipSmartTools.extractActionableEntities(
        "Contact support@example.com or visit https://docs.example.com/help or call +201001234567."
      )
    assertEquals(3, entities.size)
  }
}
