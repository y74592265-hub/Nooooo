package com.example.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.example.domain.model.CategoryDefaults
import com.example.domain.model.ClipContentType
import com.example.domain.model.ClipSource
import com.example.domain.model.SyncDirection
import com.example.domain.model.SyncProviderId
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
}

@Database(
  entities = [ClipEntity::class, CategoryEntity::class, SyncLogEntity::class],
  version = 1,
  exportSchema = false,
)
@TypeConverters(RoomConverters::class)
abstract class SmartClipboardDatabase : RoomDatabase() {
  abstract fun clipDao(): ClipDao

  abstract fun categoryDao(): CategoryDao

  abstract fun syncLogDao(): SyncLogDao

  companion object {
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
