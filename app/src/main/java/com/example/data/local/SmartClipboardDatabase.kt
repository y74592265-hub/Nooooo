package com.example.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.domain.model.CategoryDefaults
import com.example.domain.model.ClipContentType
import com.example.domain.model.ClipSource
import com.example.domain.model.ClipSyncState
import com.example.domain.model.SyncDirection
import com.example.domain.model.SyncProviderId
import com.example.domain.model.SyncQueueOperation
import com.example.domain.model.SyncQueueStatus
import com.example.domain.model.SyncStatus

class RoomConverters {
  @TypeConverter fun fromContentType(value: ClipContentType): String = value.name

  @TypeConverter
  fun toContentType(value: String): ClipContentType =
    runCatching { ClipContentType.valueOf(value) }.getOrDefault(ClipContentType.TEXT)

  @TypeConverter fun fromClipSource(value: ClipSource): String = value.name

  @TypeConverter
  fun toClipSource(value: String): ClipSource =
    runCatching { ClipSource.valueOf(value) }.getOrDefault(ClipSource.MANUAL)

  @TypeConverter fun fromClipSyncState(value: ClipSyncState): String = value.name

  @TypeConverter
  fun toClipSyncState(value: String): ClipSyncState =
    runCatching { ClipSyncState.valueOf(value) }.getOrDefault(ClipSyncState.LOCAL_ONLY)

  @TypeConverter fun fromProviderId(value: SyncProviderId): String = value.name

  @TypeConverter
  fun toProviderId(value: String): SyncProviderId =
    runCatching { SyncProviderId.valueOf(value) }.getOrDefault(SyncProviderId.TELEGRAM)

  @TypeConverter fun fromSyncDirection(value: SyncDirection): String = value.name

  @TypeConverter
  fun toSyncDirection(value: String): SyncDirection =
    runCatching { SyncDirection.valueOf(value) }.getOrDefault(SyncDirection.PUSH)

  @TypeConverter fun fromSyncStatus(value: SyncStatus): String = value.name

  @TypeConverter
  fun toSyncStatus(value: String): SyncStatus =
    runCatching { SyncStatus.valueOf(value) }.getOrDefault(SyncStatus.IDLE)

  @TypeConverter fun fromQueueOp(value: SyncQueueOperation): String = value.name

  @TypeConverter
  fun toQueueOp(value: String): SyncQueueOperation =
    runCatching { SyncQueueOperation.valueOf(value) }.getOrDefault(SyncQueueOperation.PUSH)

  @TypeConverter fun fromQueueStatus(value: SyncQueueStatus): String = value.name

  @TypeConverter
  fun toQueueStatus(value: String): SyncQueueStatus =
    runCatching { SyncQueueStatus.valueOf(value) }.getOrDefault(SyncQueueStatus.PENDING)
}

@Database(
  entities =
    [
      ClipEntity::class,
      ClipFtsEntity::class,
      SyncQueueEntity::class,
      CategoryEntity::class,
      SyncLogEntity::class,
    ],
  version = 2,
  exportSchema = false,
)
@TypeConverters(RoomConverters::class)
abstract class SmartClipboardDatabase : RoomDatabase() {
  abstract fun clipDao(): ClipDao

  abstract fun syncQueueDao(): SyncQueueDao

  abstract fun categoryDao(): CategoryDao

  abstract fun syncLogDao(): SyncLogDao

  companion object {
    val MIGRATION_1_2 =
      object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("ALTER TABLE clips ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'LOCAL_ONLY'")
          db.execSQL("ALTER TABLE clips ADD COLUMN contentSizeBytes INTEGER NOT NULL DEFAULT 0")
          db.execSQL("ALTER TABLE clips ADD COLUMN telegramMessageId TEXT")
          db.execSQL("ALTER TABLE clips ADD COLUMN discordMessageId TEXT")
          db.execSQL("ALTER TABLE clips ADD COLUMN driveRevisionId TEXT")
          db.execSQL("ALTER TABLE clips ADD COLUMN lastSyncedAt INTEGER")
          db.execSQL("ALTER TABLE clips ADD COLUMN syncError TEXT")

          db.execSQL("UPDATE clips SET contentSizeBytes = LENGTH(CAST(content AS BLOB))")
          db.execSQL("UPDATE clips SET syncStatus = 'SYNCED' WHERE remoteId IS NOT NULL")

          db.execSQL("CREATE INDEX IF NOT EXISTS `index_clips_syncStatus` ON `clips` (`syncStatus`)")

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
          db.execSQL("INSERT INTO `clips_fts`(`clips_fts`) VALUES('rebuild')")
        }
      }

    val DEFAULT_CATEGORIES =
      listOf(
        CategoryEntity(
          id = CategoryDefaults.PERSONAL_ID,
          name = "Personal",
          colorHex = 0xFF3B82F6,
          iconKey = "person",
          isDefault = true,
          sortOrder = 1,
        ),
        CategoryEntity(
          id = CategoryDefaults.WORK_ID,
          name = "Work",
          colorHex = 0xFF8B5CF6,
          iconKey = "work",
          isDefault = true,
          sortOrder = 2,
        ),
        CategoryEntity(
          id = CategoryDefaults.PROGRAMMING_ID,
          name = "Programming",
          colorHex = 0xFF06B6D4,
          iconKey = "code",
          isDefault = true,
          sortOrder = 3,
        ),
        CategoryEntity(
          id = CategoryDefaults.LINKS_ID,
          name = "Links",
          colorHex = 0xFF10B981,
          iconKey = "link",
          isDefault = true,
          sortOrder = 4,
        ),
        CategoryEntity(
          id = CategoryDefaults.NOTES_ID,
          name = "Notes",
          colorHex = 0xFFF59E0B,
          iconKey = "note",
          isDefault = true,
          sortOrder = 5,
        ),
        CategoryEntity(
          id = CategoryDefaults.IMPORTANT_ID,
          name = "Important",
          colorHex = 0xFFEF4444,
          iconKey = "star",
          isDefault = true,
          sortOrder = 6,
        ),
      )
  }
}
