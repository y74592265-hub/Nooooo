package com.example

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.core.clipboard.CaptureOutcome
import com.example.core.clipboard.ClipboardCaptureCoordinator
import com.example.core.clipboard.SensitiveContentDetector
import com.example.core.clipboard.SystemClipboardHelper
import com.example.core.clipboard.SystemClipboardSnapshot
import com.example.core.preferences.AppPreferencesRepository
import com.example.core.security.KeystoreCryptoManager
import com.example.core.sync.SyncEngine
import com.example.data.local.SmartClipboardDatabase
import com.example.data.repository.CategoryRepositoryImpl
import com.example.data.repository.ClipRepositoryImpl
import com.example.data.repository.SyncLogRepositoryImpl
import com.example.data.repository.SyncQueueRepositoryImpl
import com.example.domain.model.ClipSource
import com.example.domain.model.ClipSyncState
import com.example.domain.model.DiscordConfig
import com.example.domain.model.SyncProviderId
import com.example.domain.model.SyncQueueStatus
import com.example.domain.model.TelegramConfig
import com.example.domain.usecase.SaveClipUseCase
import com.example.domain.usecase.SearchFilter
import com.example.integrations.discord.DiscordApiService
import com.example.integrations.discord.DiscordBotUser
import com.example.integrations.discord.DiscordChannel
import com.example.integrations.discord.DiscordCreateChannelRequest
import com.example.integrations.discord.DiscordGuild
import com.example.integrations.discord.DiscordMessageDto
import com.example.integrations.discord.DiscordMessageRequest
import com.example.integrations.discord.DiscordProvider
import com.example.integrations.discord.DiscordRole
import com.example.integrations.discord.DiscordWebhookPayload
import com.example.integrations.telegram.TelegramApiService
import com.example.integrations.telegram.TelegramChat
import com.example.integrations.telegram.TelegramDeleteMessageRequest
import com.example.integrations.telegram.TelegramMessage
import com.example.integrations.telegram.TelegramProvider
import com.example.integrations.telegram.TelegramResponse
import com.example.integrations.telegram.TelegramSendMessageRequest
import com.example.integrations.telegram.TelegramUpdate
import com.example.integrations.telegram.TelegramUser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomMigrationAndQueueTest {

  private lateinit var context: Context
  private lateinit var db: SmartClipboardDatabase
  private lateinit var crypto: KeystoreCryptoManager
  private lateinit var prefs: AppPreferencesRepository
  private lateinit var clipboardHelper: SystemClipboardHelper
  private lateinit var clipRepo: ClipRepositoryImpl
  private lateinit var categoryRepo: CategoryRepositoryImpl
  private lateinit var syncLogRepo: SyncLogRepositoryImpl
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
    clipboardHelper = SystemClipboardHelper(context)
    clipRepo = ClipRepositoryImpl(db.clipDao(), crypto, clipboardHelper)
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
    val activeNet = cm.activeNetwork
    if (!online) {
      shadowCm.setDefaultNetworkActive(false)
      if (activeNet != null) {
        shadowCm.setNetworkCapabilities(activeNet, ShadowNetworkCapabilities.newInstance())
      }
    } else if (activeNet != null) {
      val caps = ShadowNetworkCapabilities.newInstance()
      Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      shadowCm.setNetworkCapabilities(activeNet, caps)
    }
  }

  @Test
  fun `migration 1 to 2 preserves all v1 rows builds FTS index and enables sync queue`() = runTest {
    val dbName = "smart_clipboard_mig_v1_v2_test.db"
    context.deleteDatabase(dbName)

    val callback =
      object : SupportSQLiteOpenHelper.Callback(1) {
        override fun onCreate(db: SupportSQLiteDatabase) {
          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `clips` (
              `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              `title` TEXT NOT NULL,
              `content` TEXT NOT NULL,
              `contentHash` TEXT NOT NULL,
              `contentType` TEXT NOT NULL,
              `categoryId` INTEGER NOT NULL,
              `tagsCsv` TEXT NOT NULL,
              `isPinned` INTEGER NOT NULL,
              `isFavorite` INTEGER NOT NULL,
              `isSensitive` INTEGER NOT NULL,
              `isTrashed` INTEGER NOT NULL,
              `trashedAt` INTEGER,
              `createdAt` INTEGER NOT NULL,
              `updatedAt` INTEGER NOT NULL,
              `source` TEXT NOT NULL,
              `remoteId` TEXT
            )
            """
              .trimIndent()
          )
          db.execSQL("CREATE INDEX IF NOT EXISTS `index_clips_contentHash` ON `clips` (`contentHash`)")
          db.execSQL("CREATE INDEX IF NOT EXISTS `index_clips_categoryId` ON `clips` (`categoryId`)")
          db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_clips_isTrashed_isPinned_updatedAt` ON `clips` (`isTrashed`, `isPinned`, `updatedAt`)"
          )
          db.execSQL("CREATE INDEX IF NOT EXISTS `index_clips_isFavorite` ON `clips` (`isFavorite`)")
          db.execSQL("CREATE INDEX IF NOT EXISTS `index_clips_contentType` ON `clips` (`contentType`)")

          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `categories` (
              `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              `name` TEXT NOT NULL,
              `colorHex` INTEGER NOT NULL,
              `iconKey` TEXT NOT NULL,
              `isDefault` INTEGER NOT NULL,
              `sortOrder` INTEGER NOT NULL
            )
            """
              .trimIndent()
          )
          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sync_logs` (
              `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              `provider` TEXT NOT NULL,
              `direction` TEXT NOT NULL,
              `status` TEXT NOT NULL,
              `message` TEXT NOT NULL,
              `itemsCount` INTEGER NOT NULL,
              `timestamp` INTEGER NOT NULL
            )
            """
              .trimIndent()
          )
          db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_logs_timestamp` ON `sync_logs` (`timestamp`)")
        }

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
      }

    val v1Helper =
      FrameworkSQLiteOpenHelperFactory()
        .create(
          SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(callback)
            .build()
        )
    val v1Db = v1Helper.writableDatabase
    v1Db.execSQL(
      "INSERT INTO `categories` (`id`, `name`, `colorHex`, `iconKey`, `isDefault`, `sortOrder`) VALUES (1, 'Personal', 4282089206, 'person', 1, 1)"
    )
    v1Db.execSQL(
      """
      INSERT INTO `clips` (`id`, `title`, `content`, `contentHash`, `contentType`, `categoryId`, `tagsCsv`, `isPinned`, `isFavorite`, `isSensitive`, `isTrashed`, `trashedAt`, `createdAt`, `updatedAt`, `source`, `remoteId`)
      VALUES (1, 'V1 Local Note', 'Migrated kotlin snippet from v1', 'hash_v1_a', 'CODE', 1, 'kotlin,mig', 1, 0, 0, 0, NULL, 1000, 2000, 'MANUAL', NULL)
      """
        .trimIndent()
    )
    v1Db.execSQL(
      """
      INSERT INTO `clips` (`id`, `title`, `content`, `contentHash`, `contentType`, `categoryId`, `tagsCsv`, `isPinned`, `isFavorite`, `isSensitive`, `isTrashed`, `trashedAt`, `createdAt`, `updatedAt`, `source`, `remoteId`)
      VALUES (2, 'V1 Remote Note', 'Synced message before upgrade', 'hash_v1_b', 'TEXT', 1, '', 0, 1, 0, 0, NULL, 1500, 2500, 'TELEGRAM', 'tg:123:99')
      """
        .trimIndent()
    )
    v1Db.execSQL(
      "INSERT INTO `sync_logs` (`id`, `provider`, `direction`, `status`, `message`, `itemsCount`, `timestamp`) VALUES (1, 'TELEGRAM', 'PULL', 'SUCCESS', 'Legacy sync', 1, 1800)"
    )
    v1Db.close()
    v1Helper.close()

    val migratedDb =
      Room.databaseBuilder(context, SmartClipboardDatabase::class.java, dbName)
        .addMigrations(
          SmartClipboardDatabase.MIGRATION_1_2,
          SmartClipboardDatabase.MIGRATION_2_3,
        )
        .allowMainThreadQueries()
        .build()

    try {
      val migratedClipRepo = ClipRepositoryImpl(migratedDb.clipDao(), crypto, clipboardHelper)
      val migratedQueueRepo = SyncQueueRepositoryImpl(migratedDb.syncQueueDao())

      val clips = migratedClipRepo.getAllActiveClipsOnce()
      assertEquals(2, clips.size)

      val localClip = clips.first { it.id == 1L }
      assertEquals("Migrated kotlin snippet from v1", localClip.content)
      assertEquals(ClipSyncState.LOCAL_ONLY, localClip.syncStatus)
      assertTrue(localClip.contentSizeBytes > 0L)

      val remoteClip = clips.first { it.id == 2L }
      assertEquals(ClipSyncState.SYNCED, remoteClip.syncStatus)
      assertEquals("tg:123:99", remoteClip.remoteId)

      // FTS4 trigger + rebuild works immediately on migrated data
      val ftsResults = migratedClipRepo.searchActiveClipsFts(SearchFilter(query = "kotlin"), limit = 50)
      assertEquals(1, ftsResults.size)
      assertEquals(1L, ftsResults.first().id)

      // sync_queue table exists and accepts jobs after migration
      migratedQueueRepo.enqueueClip(localClip.id, SyncProviderId.TELEGRAM)
      val due = migratedQueueRepo.getAllDueJobs(now = System.currentTimeMillis(), limit = 10)
      assertEquals(1, due.size)
      assertEquals(localClip.id, due.first().clipId)
    } finally {
      migratedDb.close()
      context.deleteDatabase(dbName)
    }
  }

  @Test
  fun `migration 2 to 3 preserves v2 plain and sensitive clips creates revisions and transitions tables`() =
    runTest {
      val dbName = "smart_clipboard_mig_v2_v3_test.db"
      context.deleteDatabase(dbName)
      val encSecret = crypto.encryptLocal("my-secret-password-999")

      val callback =
        object : SupportSQLiteOpenHelper.Callback(2) {
          override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(
              """
              CREATE TABLE IF NOT EXISTS `clips` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `contentHash` TEXT NOT NULL,
                `contentType` TEXT NOT NULL,
                `categoryId` INTEGER NOT NULL,
                `tagsCsv` TEXT NOT NULL,
                `isPinned` INTEGER NOT NULL,
                `isFavorite` INTEGER NOT NULL,
                `isSensitive` INTEGER NOT NULL,
                `isTrashed` INTEGER NOT NULL,
                `trashedAt` INTEGER,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `source` TEXT NOT NULL,
                `remoteId` TEXT,
                `syncStatus` TEXT NOT NULL,
                `contentSizeBytes` INTEGER NOT NULL,
                `telegramMessageId` TEXT,
                `discordMessageId` TEXT,
                `driveRevisionId` TEXT,
                `lastSyncedAt` INTEGER,
                `syncError` TEXT
              )
              """
                .trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_clips_contentHash` ON `clips` (`contentHash`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_clips_categoryId` ON `clips` (`categoryId`)")
            db.execSQL(
              "CREATE INDEX IF NOT EXISTS `index_clips_isTrashed_isPinned_updatedAt` ON `clips` (`isTrashed`, `isPinned`, `updatedAt`)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_clips_isFavorite` ON `clips` (`isFavorite`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_clips_contentType` ON `clips` (`contentType`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_clips_syncStatus` ON `clips` (`syncStatus`)")
            db.execSQL(
              "CREATE VIRTUAL TABLE IF NOT EXISTS `clips_fts` USING FTS4(`title` TEXT, `content` TEXT, `tagsCsv` TEXT, content=`clips`)"
            )
            db.execSQL(
              "CREATE TRIGGER IF NOT EXISTS `room_fts_content_sync_clips_fts_BEFORE_UPDATE` BEFORE UPDATE ON `clips` BEGIN DELETE FROM `clips_fts` WHERE `docid`=OLD.`rowid`; END"
            )
            db.execSQL(
              "CREATE TRIGGER IF NOT EXISTS `room_fts_content_sync_clips_fts_BEFORE_DELETE` BEFORE DELETE ON `clips` BEGIN DELETE FROM `clips_fts` WHERE `docid`=OLD.`rowid`; END"
            )
            db.execSQL(
              "CREATE TRIGGER IF NOT EXISTS `room_fts_content_sync_clips_fts_AFTER_UPDATE` AFTER UPDATE ON `clips` BEGIN INSERT INTO `clips_fts`(`docid`, `title`, `content`, `tagsCsv`) VALUES (NEW.`rowid`, NEW.`title`, NEW.`content`, NEW.`tagsCsv`); END"
            )
            db.execSQL(
              "CREATE TRIGGER IF NOT EXISTS `room_fts_content_sync_clips_fts_AFTER_INSERT` AFTER INSERT ON `clips` BEGIN INSERT INTO `clips_fts`(`docid`, `title`, `content`, `tagsCsv`) VALUES (NEW.`rowid`, NEW.`title`, NEW.`content`, NEW.`tagsCsv`); END"
            )
            db.execSQL(
              """
              CREATE TABLE IF NOT EXISTS `sync_queue` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `clipId` INTEGER NOT NULL,
                `provider` TEXT NOT NULL,
                `operation` TEXT NOT NULL,
                `remoteTargetId` TEXT,
                `status` TEXT NOT NULL,
                `retryCount` INTEGER NOT NULL,
                `nextRetryAt` INTEGER NOT NULL,
                `lastError` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
              )
              """
                .trimIndent()
            )
            db.execSQL(
              "CREATE INDEX IF NOT EXISTS `index_sync_queue_provider_status_nextRetryAt` ON `sync_queue` (`provider`, `status`, `nextRetryAt`)"
            )
            db.execSQL(
              "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_queue_clipId_provider_operation` ON `sync_queue` (`clipId`, `provider`, `operation`)"
            )
            db.execSQL(
              """
              CREATE TABLE IF NOT EXISTS `categories` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `colorHex` INTEGER NOT NULL,
                `iconKey` TEXT NOT NULL,
                `isDefault` INTEGER NOT NULL,
                `sortOrder` INTEGER NOT NULL
              )
              """
                .trimIndent()
            )
            db.execSQL(
              """
              CREATE TABLE IF NOT EXISTS `sync_logs` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `provider` TEXT NOT NULL,
                `direction` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `message` TEXT NOT NULL,
                `itemsCount` INTEGER NOT NULL,
                `timestamp` INTEGER NOT NULL
              )
              """
                .trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_logs_timestamp` ON `sync_logs` (`timestamp`)")
          }

          override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }

      val v2Helper =
        FrameworkSQLiteOpenHelperFactory()
          .create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
              .name(dbName)
              .callback(callback)
              .build()
          )
      val v2Db = v2Helper.writableDatabase
      v2Db.execSQL(
        "INSERT INTO `categories` (`id`, `name`, `colorHex`, `iconKey`, `isDefault`, `sortOrder`) VALUES (1, 'Personal', 4282089206, 'person', 1, 1)"
      )
      v2Db.execSQL(
        """
        INSERT INTO `clips` (`id`, `title`, `content`, `contentHash`, `contentType`, `categoryId`, `tagsCsv`, `isPinned`, `isFavorite`, `isSensitive`, `isTrashed`, `trashedAt`, `createdAt`, `updatedAt`, `source`, `remoteId`, `syncStatus`, `contentSizeBytes`, `telegramMessageId`, `discordMessageId`, `driveRevisionId`, `lastSyncedAt`, `syncError`)
        VALUES (10, 'V2 Draft', 'Draft body text ready for v3', 'hash_v2_plain', 'NOTE', 1, 'draft', 0, 0, 0, 0, NULL, 1000, 2000, 'MANUAL', NULL, 'LOCAL_ONLY', 25, NULL, NULL, NULL, NULL, NULL)
        """
          .trimIndent()
      )
      v2Db.execSQL(
        """
        INSERT INTO `clips` (`id`, `title`, `content`, `contentHash`, `contentType`, `categoryId`, `tagsCsv`, `isPinned`, `isFavorite`, `isSensitive`, `isTrashed`, `trashedAt`, `createdAt`, `updatedAt`, `source`, `remoteId`, `syncStatus`, `contentSizeBytes`, `telegramMessageId`, `discordMessageId`, `driveRevisionId`, `lastSyncedAt`, `syncError`)
        VALUES (11, 'V2 Secret', '$encSecret', 'hash_v2_secret', 'TEXT', 1, '', 0, 0, 1, 0, NULL, 1000, 2100, 'MANUAL', NULL, 'LOCAL_ONLY', 22, NULL, NULL, NULL, NULL, NULL)
        """
          .trimIndent()
      )
      v2Db.close()
      v2Helper.close()

      val migratedDb =
        Room.databaseBuilder(context, SmartClipboardDatabase::class.java, dbName)
          .addMigrations(SmartClipboardDatabase.MIGRATION_2_3)
          .allowMainThreadQueries()
          .build()
      try {
        val repo = ClipRepositoryImpl(migratedDb.clipDao(), crypto, clipboardHelper)
        val clips = repo.getAllActiveClipsOnce()
        assertEquals(2, clips.size)
        val plain = clips.first { it.id == 10L }
        assertEquals(1, plain.versionNumber)
        assertEquals(0, plain.useCount)
        val secret = clips.first { it.id == 11L }
        assertEquals("my-secret-password-999", secret.content)

        // SQL IME isolation strictly skips sensitive row
        val imeRows = migratedDb.clipDao().getImeCandidateProjections(limit = 20)
        assertEquals(1, imeRows.size)
        assertEquals(10L, imeRows.first().id)

        // clip_transitions & clip_revisions operate cleanly after v2->v3 migration
        migratedDb.clipTransitionDao().recordTransition(fromClipId = 10L, toClipId = 10L, nowMs = 5000L)
        assertEquals(1, migratedDb.clipTransitionDao().getTopTransitionsFrom(10L).size)
      } finally {
        migratedDb.close()
        context.deleteDatabase(dbName)
      }
    }

  @Test
  fun `sensitive detector classifies OTP password API token Luhn card and system flag accurately`() {
    val detector = SensitiveContentDetector()

    assertTrue(detector.analyze("482910").isSensitive)
    assertTrue(detector.analyze("رمز التحقق الخاص بك: 991234").isSensitive)
    assertTrue(detector.analyze("password: MySuperSecret#99").isSensitive)
    assertTrue(detector.analyze("sk-proj-1234567890abcdefghijXYZ").isSensitive)
    assertTrue(detector.analyze("4532015112830366").isSensitive) // Valid Luhn test card
    assertTrue(detector.analyze("non matching text", systemSensitiveFlag = true).isSensitive)

    val safeResult = detector.analyze("Jetpack Compose lazy pagination note")
    assertFalse(safeResult.isSensitive)
  }

  @Test
  fun `capture coordinator blocks sensitive cloud queue honors skip and exclusions and deduplicates normal clips`() =
    runTest {
      val tgApi = dummyOkTelegramApi()
      val dcApi = dummyOkDiscordApi()
      val tgProvider = TelegramProvider(tgApi, prefs, categoryRepo)
      val dcProvider = DiscordProvider(dcApi, prefs, categoryRepo)
      prefs.saveTelegramConfig(TelegramConfig(enabled = true, botToken = "bot123", chatId = "42", autoSync = true))
      prefs.saveDiscordConfig(DiscordConfig(enabled = true, botToken = "dc_bot", channelId = "777", autoSync = true))
      prefs.updateExcludedSourceAppsCsv("bitwarden, keepass_local")
      prefs.updateAutoDetectSensitivePatterns(true)
      prefs.updateBlockSensitiveCloudSync(true)
      prefs.updateSkipAutoSaveSensitive(false)

      val engine = SyncEngine(context, setOf(tgProvider, dcProvider), clipRepo, categoryRepo, syncLogRepo, syncQueueRepo, prefs)
      val saveUseCase = SaveClipUseCase(clipRepo, categoryRepo, clipboardHelper)
      val coordinator =
        ClipboardCaptureCoordinator(
          clipboardHelper = clipboardHelper,
          clipRepository = clipRepo,
          saveClipUseCase = saveUseCase,
          syncQueueRepository = syncQueueRepo,
          cryptoManager = crypto,
          prefs = prefs,
          syncEngine = engine,
          sensitiveDetector = SensitiveContentDetector(),
        )

      // 1. Excluded package / keyword skips capture completely
      val excludedPkg =
        coordinator.captureFromSystemClipboard(
          source = ClipSource.IME,
          foregroundPackageHint = "com.x8bit.bitwarden",
          overrideSnapshot = SystemClipboardSnapshot("vault item"),
        )
      assertTrue(excludedPkg is CaptureOutcome.ExcludedByRule)
      assertEquals(0, clipRepo.getAllActiveClipsOnce().size)

      // 2. Sensitive OTP saves encrypted locally as LOCAL_ONLY and NEVER enters SyncQueue
      val sensitiveOutcome =
        coordinator.captureFromSystemClipboard(
          source = ClipSource.CLIPBOARD,
          overrideSnapshot = SystemClipboardSnapshot("Verification code 492810"),
        )
      assertTrue(sensitiveOutcome is CaptureOutcome.Saved)
      val savedSensitive = sensitiveOutcome as CaptureOutcome.Saved
      assertTrue(savedSensitive.autoMarkedSensitive)
      assertTrue(savedSensitive.queuedProviders.isEmpty())
      assertEquals(ClipSyncState.LOCAL_ONLY, savedSensitive.clip.syncStatus)
      assertTrue(syncQueueRepo.getAllDueJobs(now = Long.MAX_VALUE, limit = 20).isEmpty())

      // Check database raw stored value is encrypted rather than plaintext
      val rawEntity = db.clipDao().getClipById(savedSensitive.clip.id)
      assertNotNull(rawEntity)
      assertFalse(rawEntity!!.content.contains("492810"))

      // 3. skipAutoSaveSensitive = true prevents saving sensitive items locally too
      prefs.updateSkipAutoSaveSensitive(true)
      val skippedOutcome =
        coordinator.captureFromSystemClipboard(
          source = ClipSource.CLIPBOARD,
          overrideSnapshot = SystemClipboardSnapshot("password: AnotherSecret#22"),
        )
      assertTrue(skippedOutcome is CaptureOutcome.SkippedSensitive)
      assertEquals(1, clipRepo.getAllActiveClipsOnce().size)

      // 4. Normal snippet saves, deduplicates, and enqueues PUSH jobs for enabled auto-sync providers
      val normalOutcome =
        coordinator.captureFromSystemClipboard(
          source = ClipSource.CLIPBOARD,
          overrideSnapshot = SystemClipboardSnapshot("https://developer.android.com/compose"),
        )
      assertTrue(normalOutcome is CaptureOutcome.Saved)
      val savedNormal = normalOutcome as CaptureOutcome.Saved
      assertFalse(savedNormal.autoMarkedSensitive)
      assertEquals(setOf(SyncProviderId.TELEGRAM, SyncProviderId.DISCORD), savedNormal.queuedProviders.toSet())
      assertEquals(ClipSyncState.PENDING, savedNormal.clip.syncStatus)

      // Exact repeat clipboard read is ignored via SHA-256 cache
      val duplicateRead =
        coordinator.captureFromSystemClipboard(
          source = ClipSource.CLIPBOARD,
          overrideSnapshot = SystemClipboardSnapshot("https://developer.android.com/compose"),
        )
      assertTrue(duplicateRead is CaptureOutcome.AlreadyCaptured)

      val jobs = syncQueueRepo.getAllDueJobs(now = Long.MAX_VALUE, limit = 20)
      assertEquals(2, jobs.size)
    }

  @Test
  fun `sync queue handles 429 retry-after backoff isolates failing provider and supports manual requeue`() =
    runTest {
      val tg429Body = """{"ok":false,"error_code":429,"description":"Too Many Requests","parameters":{"retry_after":120}}"""
      var failTelegram = true
      var telegramSendCalls = 0
      var discordSendCalls = 0

      val tgApi =
        object : TelegramApiService {
          override suspend fun getMe(token: String) =
            Response.success(TelegramResponse(ok = true, result = TelegramUser(1L, "clipbot")))

          override suspend fun sendMessage(token: String, body: TelegramSendMessageRequest) =
            if (failTelegram) {
              telegramSendCalls++
              Response.error<TelegramResponse<TelegramMessage>>(
                429,
                tg429Body.toResponseBody("application/json".toMediaType()),
              )
            } else {
              telegramSendCalls++
              Response.success(
                TelegramResponse(ok = true, result = TelegramMessage(888L, TelegramChat(body.chatId.toLongOrNull() ?: 123L), body.text))
              )
            }

          override suspend fun getUpdates(token: String, offset: Long?, limit: Int, timeout: Int) =
            Response.success(TelegramResponse(ok = true, result = emptyList<TelegramUpdate>()))

          override suspend fun deleteMessage(token: String, body: TelegramDeleteMessageRequest) =
            Response.success(TelegramResponse(ok = true, result = true))
        }

      val dcApi =
        object : DiscordApiService {
          override suspend fun executeWebhook(webhookUrl: String, payload: DiscordWebhookPayload) =
            Response.success(Unit)

          override suspend fun getWebhookInfo(webhookUrl: String) =
            Response.success(DiscordChannel("99", "vault"))

          override suspend fun getCurrentBotUser(botAuthorization: String) =
            Response.success(DiscordBotUser("b1", "Bot", bot = true))

          override suspend fun getBotGuilds(botAuthorization: String) =
            Response.success(emptyList<DiscordGuild>())

          override suspend fun getGuildChannels(botAuthorization: String, guildId: String) =
            Response.success(emptyList<DiscordChannel>())

          override suspend fun getGuildRoles(botAuthorization: String, guildId: String) =
            Response.success(emptyList<DiscordRole>())

          override suspend fun createGuildChannel(
            botAuthorization: String,
            guildId: String,
            body: DiscordCreateChannelRequest,
          ) = Response.success(DiscordChannel("99", body.name))

          override suspend fun getChannel(botAuthorization: String, channelId: String) =
            Response.success(DiscordChannel("99", "vault"))

          override suspend fun sendChannelMessage(
            botAuthorization: String,
            channelId: String,
            body: DiscordMessageRequest,
          ): Response<DiscordMessageDto> {
            discordSendCalls++
            return Response.success(DiscordMessageDto("dc_msg_500", body.content))
          }

          override suspend fun getChannelMessages(
            botAuthorization: String,
            channelId: String,
            afterMessageId: String?,
            limit: Int,
            beforeMessageId: String?,
          ) = Response.success(emptyList<DiscordMessageDto>())

          override suspend fun deleteChannelMessage(
            botAuthorization: String,
            channelId: String,
            messageId: String,
          ) = Response.success(Unit)
        }

      prefs.saveTelegramConfig(TelegramConfig(enabled = true, botToken = "tg_tok", chatId = "123", autoSync = true))
      prefs.saveDiscordConfig(DiscordConfig(enabled = true, botToken = "dc_tok", channelId = "99", autoSync = true))

      val tgProvider = TelegramProvider(tgApi, prefs, categoryRepo)
      val dcProvider = DiscordProvider(dcApi, prefs, categoryRepo)
      val engine = SyncEngine(context, setOf(tgProvider, dcProvider), clipRepo, categoryRepo, syncLogRepo, syncQueueRepo, prefs)

      val saved = clipRepo.saveNewOrDeduplicate("", "Cross-provider queue item")
      syncQueueRepo.enqueueClip(saved.clipId, SyncProviderId.TELEGRAM)
      syncQueueRepo.enqueueClip(saved.clipId, SyncProviderId.DISCORD)

      val beforeMs = System.currentTimeMillis()
      val syncOk = engine.syncAll()
      assertFalse(syncOk) // Telegram returned retryable 429

      // Discord succeeded independently despite Telegram 429 failure
      assertEquals(1, discordSendCalls)
      assertEquals(1, telegramSendCalls)
      val updatedClip = clipRepo.getClipById(saved.clipId)!!
      assertEquals("discord:99:dc_msg_500", updatedClip.discordMessageId)

      val allJobs = syncQueueRepo.observeAllJobs().first()
      val tgJob = allJobs.first { it.provider == SyncProviderId.TELEGRAM }
      val dcJob = allJobs.first { it.provider == SyncProviderId.DISCORD }
      assertEquals(SyncQueueStatus.SYNCED, dcJob.status)
      assertEquals(SyncQueueStatus.FAILED, tgJob.status)
      assertEquals(1, tgJob.retryCount)
      assertTrue(tgJob.nextRetryAt >= beforeMs + 100_000L)

      // Immediate second sync skips Telegram while backoff window hasn't elapsed
      engine.syncAll()
      assertEquals(1, telegramSendCalls)

      // Manual retryFailedQueueJobs resets Telegram FAILED -> PENDING and succeeds without duplicating Discord
      failTelegram = false
      engine.retryFailedQueueJobs(SyncProviderId.TELEGRAM)
      engine.syncAll()
      assertEquals(2, telegramSendCalls)
      assertEquals(1, discordSendCalls)

      val jobsAfterRetry = syncQueueRepo.observeAllJobs().first()
      assertEquals(SyncQueueStatus.SYNCED, jobsAfterRetry.first { it.provider == SyncProviderId.TELEGRAM }.status)
    }

  private fun dummyOkTelegramApi() =
    object : TelegramApiService {
      override suspend fun getMe(token: String) =
        Response.success(TelegramResponse(ok = true, result = TelegramUser(1L, "bot")))

      override suspend fun sendMessage(token: String, body: TelegramSendMessageRequest) =
        Response.success(TelegramResponse(ok = true, result = TelegramMessage(10L, TelegramChat(42L), body.text)))

      override suspend fun getUpdates(token: String, offset: Long?, limit: Int, timeout: Int) =
        Response.success(TelegramResponse(ok = true, result = emptyList<TelegramUpdate>()))

      override suspend fun deleteMessage(token: String, body: TelegramDeleteMessageRequest) =
        Response.success(TelegramResponse(ok = true, result = true))
    }

  private fun dummyOkDiscordApi() =
    object : DiscordApiService {
      override suspend fun executeWebhook(webhookUrl: String, payload: DiscordWebhookPayload) =
        Response.success(Unit)

      override suspend fun getWebhookInfo(webhookUrl: String) =
        Response.success(DiscordChannel("777", "vault"))

      override suspend fun getCurrentBotUser(botAuthorization: String) =
        Response.success(DiscordBotUser("b1", "Bot", bot = true))

      override suspend fun getBotGuilds(botAuthorization: String) =
        Response.success(emptyList<DiscordGuild>())

      override suspend fun getGuildChannels(botAuthorization: String, guildId: String) =
        Response.success(emptyList<DiscordChannel>())

      override suspend fun getGuildRoles(botAuthorization: String, guildId: String) =
        Response.success(emptyList<DiscordRole>())

      override suspend fun createGuildChannel(
        botAuthorization: String,
        guildId: String,
        body: DiscordCreateChannelRequest,
      ) = Response.success(DiscordChannel("777", body.name))

      override suspend fun getChannel(botAuthorization: String, channelId: String) =
        Response.success(DiscordChannel("777", "vault"))

      override suspend fun sendChannelMessage(
        botAuthorization: String,
        channelId: String,
        body: DiscordMessageRequest,
      ) = Response.success(DiscordMessageDto("1", body.content))

      override suspend fun getChannelMessages(
        botAuthorization: String,
        channelId: String,
        afterMessageId: String?,
        limit: Int,
        beforeMessageId: String?,
      ) = Response.success(emptyList<DiscordMessageDto>())

      override suspend fun deleteChannelMessage(
        botAuthorization: String,
        channelId: String,
        messageId: String,
      ) = Response.success(Unit)
    }
}
