package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipDao {
  @Query(
    "SELECT * FROM clips WHERE isTrashed = 0 ORDER BY isPinned DESC, updatedAt DESC"
  )
  fun observeActiveClips(): Flow<List<ClipEntity>>

  @Query("SELECT * FROM clips WHERE isTrashed = 0 AND isFavorite = 1 ORDER BY isPinned DESC, updatedAt DESC")
  fun observeFavoriteClips(): Flow<List<ClipEntity>>

  @Query("SELECT * FROM clips WHERE isTrashed = 1 ORDER BY trashedAt DESC")
  fun observeTrashedClips(): Flow<List<ClipEntity>>

  @Query("SELECT * FROM clips WHERE isTrashed = 0 AND categoryId = :categoryId ORDER BY isPinned DESC, updatedAt DESC")
  fun observeClipsByCategory(categoryId: Long): Flow<List<ClipEntity>>

  @Query("SELECT * FROM clips WHERE id = :id LIMIT 1")
  fun observeClipById(id: Long): Flow<ClipEntity?>

  @Query("SELECT * FROM clips WHERE id = :id LIMIT 1")
  suspend fun getClipById(id: Long): ClipEntity?

  @Query("SELECT * FROM clips WHERE contentHash = :hash AND isTrashed = 0 LIMIT 1")
  suspend fun findActiveByHash(hash: String): ClipEntity?

  @Query("SELECT * FROM clips WHERE remoteId = :remoteId LIMIT 1")
  suspend fun findByRemoteId(remoteId: String): ClipEntity?

  @Query("SELECT * FROM clips WHERE isTrashed = 0 ORDER BY updatedAt DESC")
  suspend fun getAllActiveClipsOnce(): List<ClipEntity>

  @Query("SELECT * FROM clips ORDER BY updatedAt DESC LIMIT :limit OFFSET :offset")
  suspend fun getClipsPaged(limit: Int, offset: Int): List<ClipEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertClip(clip: ClipEntity): Long

  @Update
  suspend fun updateClip(clip: ClipEntity)

  @Query("UPDATE clips SET isPinned = :pinned, updatedAt = :updatedAt WHERE id = :id")
  suspend fun setPinned(id: Long, pinned: Boolean, updatedAt: Long)

  @Query("UPDATE clips SET isFavorite = :favorite, updatedAt = :updatedAt WHERE id = :id")
  suspend fun setFavorite(id: Long, favorite: Boolean, updatedAt: Long)

  @Query("UPDATE clips SET categoryId = :categoryId, updatedAt = :updatedAt WHERE id IN (:ids)")
  suspend fun moveClipsToCategory(ids: List<Long>, categoryId: Long, updatedAt: Long)

  @Query("UPDATE clips SET isTrashed = 1, trashedAt = :trashedAt, isPinned = 0 WHERE id IN (:ids)")
  suspend fun softDeleteClips(ids: List<Long>, trashedAt: Long)

  @Query("UPDATE clips SET isTrashed = 0, trashedAt = NULL, updatedAt = :updatedAt WHERE id IN (:ids)")
  suspend fun restoreClips(ids: List<Long>, updatedAt: Long)

  @Query("DELETE FROM clips WHERE id IN (:ids)")
  suspend fun deleteClipsForever(ids: List<Long>)

  @Query("DELETE FROM clips WHERE isTrashed = 1")
  suspend fun emptyTrash()

  @Query("DELETE FROM clips WHERE isTrashed = 0 AND isPinned = 0 AND isFavorite = 0 AND updatedAt < :cutoffTimestamp")
  suspend fun purgeExpiredHistory(cutoffTimestamp: Long): Int

  @Query("DELETE FROM clips")
  suspend fun deleteAllClips()
}

data class CategoryCountRow(
  val id: Long,
  val name: String,
  val colorHex: Long,
  val iconKey: String,
  val isDefault: Boolean,
  val sortOrder: Int,
  val clipCount: Int,
)

@Dao
interface CategoryDao {
  @Query(
    """
    SELECT c.id, c.name, c.colorHex, c.iconKey, c.isDefault, c.sortOrder,
           COUNT(cl.id) AS clipCount
    FROM categories c
    LEFT JOIN clips cl ON c.id = cl.categoryId AND cl.isTrashed = 0
    GROUP BY c.id
    ORDER BY c.sortOrder ASC, c.id ASC
    """
  )
  fun observeCategoriesWithCount(): Flow<List<CategoryCountRow>>

  @Query("SELECT * FROM categories ORDER BY sortOrder ASC, id ASC")
  suspend fun getAllCategoriesOnce(): List<CategoryEntity>

  @Query("SELECT * FROM categories WHERE LOWER(name) = LOWER(:name) LIMIT 1")
  suspend fun findByName(name: String): CategoryEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertCategory(category: CategoryEntity): Long

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertCategories(categories: List<CategoryEntity>)

  @Update
  suspend fun updateCategory(category: CategoryEntity)

  @Query("DELETE FROM categories WHERE id = :id AND isDefault = 0")
  suspend fun deleteCustomCategory(id: Long)

  @Query("UPDATE clips SET categoryId = 1 WHERE categoryId = :deletedCategoryId")
  suspend fun reassignClipsToPersonal(deletedCategoryId: Long)
}

@Dao
interface SyncLogDao {
  @Query("SELECT * FROM sync_logs ORDER BY timestamp DESC LIMIT 100")
  fun observeRecentLogs(): Flow<List<SyncLogEntity>>

  @Insert
  suspend fun insertLog(log: SyncLogEntity): Long

  @Query("DELETE FROM sync_logs")
  suspend fun clearLogs()
}
