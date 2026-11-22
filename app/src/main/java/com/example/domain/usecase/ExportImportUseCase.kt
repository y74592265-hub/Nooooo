package com.example.domain.usecase

import com.example.core.security.KeystoreCryptoManager
import com.example.domain.model.BackupCategoryDto
import com.example.domain.model.BackupClipDto
import com.example.domain.model.BackupPayload
import com.example.domain.model.CategoryDefaults
import com.example.domain.model.ClipSource
import com.example.domain.model.EncryptedBackupEnvelope
import com.example.domain.repository.CategoryRepository
import com.example.domain.repository.ClipRepository
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

enum class ExportFormat(val extension: String, val mimeType: String) {
  JSON("json", "application/json"),
  CSV("csv", "text/csv"),
  TXT("txt", "text/plain"),
  ENCRYPTED_BACKUP("scbak", "application/octet-stream"),
}

data class RestoreSummary(
  val importedCount: Int,
  val deduplicatedCount: Int,
  val checksumVerified: Boolean,
)

class ExportImportUseCase
@Inject
constructor(
  private val clipRepository: ClipRepository,
  private val categoryRepository: CategoryRepository,
  private val cryptoManager: KeystoreCryptoManager,
  private val json: Json,
) {

  suspend fun buildBackupPayload(): BackupPayload {
    categoryRepository.ensureDefaultCategories()
    val categories = categoryRepository.getAllCategoriesOnce()
    val categoryMap = categories.associateBy { it.id }
    val clips = clipRepository.getAllActiveClipsOnce()

    val catDtos =
      categories.map {
        BackupCategoryDto(
          name = it.name,
          colorHex = it.colorHex,
          iconKey = it.iconKey,
          isDefault = it.isDefault,
        )
      }
    val clipDtos =
      clips.map { clip ->
        val cat = categoryMap[clip.categoryId]
        BackupClipDto(
          title = clip.title,
          content = clip.content,
          contentHash = clip.contentHash,
          contentType = clip.contentType,
          categoryName = cat?.name ?: "Personal",
          categoryColorHex = cat?.colorHex ?: 0xFF3B82F6,
          tags = clip.tags,
          isPinned = clip.isPinned,
          isFavorite = clip.isFavorite,
          isSensitive = clip.isSensitive,
          createdAt = clip.createdAt,
          updatedAt = clip.updatedAt,
          source = clip.source,
        )
      }

    val rawPayload = BackupPayload(categories = catDtos, clips = clipDtos, sha256Checksum = "")
    val checksum = computePayloadChecksum(rawPayload)
    return rawPayload.copy(sha256Checksum = checksum)
  }

  suspend fun exportAsJson(): String = json.encodeToString(buildBackupPayload())

  suspend fun exportEncryptedBackup(passphrase: String): String {
    val payloadJson = exportAsJson()
    val envelope = cryptoManager.encryptPortableBackup(payloadJson, passphrase)
    return json.encodeToString(envelope)
  }

  suspend fun exportAsCsv(): String {
    val categories = categoryRepository.getAllCategoriesOnce().associateBy { it.id }
    val clips = clipRepository.getAllActiveClipsOnce()
    val sb = StringBuilder()
    sb.appendLine("id,title,content,type,category,tags,pinned,favorite,sensitive,created_at,updated_at")
    val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    for (c in clips) {
      val catName = categories[c.categoryId]?.name ?: "Personal"
      sb.append(c.id).append(',')
      sb.append(escapeCsv(c.title)).append(',')
      sb.append(escapeCsv(c.content)).append(',')
      sb.append(c.contentType.name).append(',')
      sb.append(escapeCsv(catName)).append(',')
      sb.append(escapeCsv(c.tags.joinToString("|"))).append(',')
      sb.append(c.isPinned).append(',')
      sb.append(c.isFavorite).append(',')
      sb.append(c.isSensitive).append(',')
      sb.append(isoFormat.format(Date(c.createdAt))).append(',')
      sb.appendLine(isoFormat.format(Date(c.updatedAt)))
    }
    return sb.toString()
  }

  suspend fun exportAsTxt(): String {
    val categories = categoryRepository.getAllCategoriesOnce().associateBy { it.id }
    val clips = clipRepository.getAllActiveClipsOnce()
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    val sb = StringBuilder()
    sb.appendLine("Smart Clipboard Export (${clips.size} items)")
    sb.appendLine("========================================")
    for (c in clips) {
      val catName = categories[c.categoryId]?.name ?: "Personal"
      sb.appendLine()
      if (c.title.isNotBlank()) sb.appendLine("Title: ${c.title}")
      sb.appendLine("Category: $catName | Type: ${c.contentType.name} | Date: ${dateFormat.format(Date(c.updatedAt))}")
      if (c.tags.isNotEmpty()) sb.appendLine("Tags: ${c.tags.joinToString(", ")}")
      sb.appendLine("---")
      sb.appendLine(c.content)
      sb.appendLine("========================================")
    }
    return sb.toString()
  }

  suspend fun restoreFromRawContent(rawContent: String, passphrase: String? = null): RestoreSummary {
    val trimmed = rawContent.trim()
    val payloadJson =
      if (trimmed.contains("SMART_CLIPBOARD_ENC_V1")) {
        val envelope = json.decodeFromString<EncryptedBackupEnvelope>(trimmed)
        require(!passphrase.isNullOrBlank()) { "Passphrase required to decrypt backup." }
        cryptoManager.decryptPortableBackup(envelope, passphrase)
      } else {
        trimmed
      }

    val payload = json.decodeFromString<BackupPayload>(payloadJson)
    val expectedChecksum = computePayloadChecksum(payload.copy(sha256Checksum = ""))
    val checksumOk =
      payload.sha256Checksum.isBlank() || payload.sha256Checksum == expectedChecksum
    require(checksumOk) { "Backup integrity verification failed (SHA-256 mismatch)." }

    categoryRepository.ensureDefaultCategories()
    val categoryIdByName = mutableMapOf<String, Long>()
    for (cat in categoryRepository.getAllCategoriesOnce()) {
      categoryIdByName[cat.name.lowercase()] = cat.id
    }
    for (dto in payload.categories) {
      val key = dto.name.trim().lowercase()
      if (!categoryIdByName.containsKey(key)) {
        val createdId = categoryRepository.createCategory(dto.name, dto.colorHex, dto.iconKey)
        categoryIdByName[key] = createdId
      }
    }

    var imported = 0
    var deduplicated = 0
    for (clipDto in payload.clips) {
      val catId =
        categoryIdByName[clipDto.categoryName.trim().lowercase()] ?: CategoryDefaults.PERSONAL_ID
      val res =
        clipRepository.saveNewOrDeduplicate(
          title = clipDto.title,
          content = clipDto.content,
          contentType = clipDto.contentType,
          categoryId = catId,
          tags = clipDto.tags,
          isPinned = clipDto.isPinned,
          isFavorite = clipDto.isFavorite,
          isSensitive = clipDto.isSensitive,
          source = ClipSource.RESTORE,
        )
      if (res.wasDuplicateMerged) deduplicated++ else imported++
    }

    return RestoreSummary(
      importedCount = imported,
      deduplicatedCount = deduplicated,
      checksumVerified = payload.sha256Checksum.isNotBlank(),
    )
  }

  fun computePayloadChecksum(payloadWithoutChecksum: BackupPayload): String {
    val canonical = json.encodeToString(payloadWithoutChecksum.copy(sha256Checksum = ""))
    return cryptoManager.sha256(canonical)
  }

  private fun escapeCsv(value: String): String {
    val escaped = value.replace("\"", "\"\"")
    return "\"$escaped\""
  }
}
