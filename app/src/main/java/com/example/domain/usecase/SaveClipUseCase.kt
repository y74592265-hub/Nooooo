package com.example.domain.usecase

import com.example.core.clipboard.SystemClipboardHelper
import com.example.core.preferences.AppPreferencesRepository
import com.example.core.security.IntelligencePrivacyGate
import com.example.core.security.KeystoreCryptoManager
import com.example.domain.model.ClipContentType
import com.example.domain.model.ClipSource
import com.example.domain.model.ClipSyncState
import com.example.domain.repository.CategoryRepository
import com.example.domain.repository.ClipLineageRepository
import com.example.domain.repository.ClipRepository
import com.example.domain.repository.SaveClipResult
import javax.inject.Inject

class SaveClipUseCase
@Inject
constructor(
  private val clipRepository: ClipRepository,
  private val categoryRepository: CategoryRepository,
  private val clipboardHelper: SystemClipboardHelper,
  private val lineageRepository: ClipLineageRepository? = null,
  private val preferencesRepository: AppPreferencesRepository? = null,
  private val cryptoManager: KeystoreCryptoManager? = null,
) {
  /** Secondary 3-arg constructor preserved for existing unit/Robolectric tests. */
  constructor(
    clipRepository: ClipRepository,
    categoryRepository: CategoryRepository,
    clipboardHelper: SystemClipboardHelper,
  ) : this(clipRepository, categoryRepository, clipboardHelper, null, null, null)

  suspend operator fun invoke(
    title: String,
    content: String,
    contentType: ClipContentType? = null,
    categoryId: Long? = null,
    tags: List<String> = emptyList(),
    isPinned: Boolean = false,
    isFavorite: Boolean = false,
    isSensitive: Boolean = false,
    source: ClipSource = ClipSource.MANUAL,
    remoteId: String? = null,
    syncStatus: ClipSyncState? = null,
  ): SaveClipResult? {
    val trimmed = content.trim()
    if (trimmed.isEmpty()) return null
    categoryRepository.ensureDefaultCategories()
    val resolvedType = contentType ?: clipboardHelper.classifyContent(trimmed)

    // Attempt Smart Lineage draft stacking only for non-sensitive new clips (no remote sync override)
    if (
      lineageRepository != null &&
        cryptoManager != null &&
        remoteId == null &&
        !isSensitive
    ) {
      val settings = preferencesRepository?.latestSettingsSnapshot
      val lineageAllowed =
        if (settings != null) IntelligencePrivacyGate.canGroupLineage(isSensitive, settings)
        else true
      if (lineageAllowed) {
        val hash = cryptoManager.sha256(trimmed)
        if (clipRepository.findActiveByHash(hash) == null) {
          val lineageMerged =
            lineageRepository.tryMergeIntoLineage(
              newContent = trimmed,
              newContentHash = hash,
              contentType = resolvedType,
              tags = tags,
              isSensitive = isSensitive,
              source = source,
            )
          if (lineageMerged != null) return lineageMerged
        }
      }
    }

    return clipRepository.saveNewOrDeduplicate(
      title = title,
      content = trimmed,
      contentType = resolvedType,
      categoryId = categoryId,
      tags = tags,
      isPinned = isPinned,
      isFavorite = isFavorite,
      isSensitive = isSensitive,
      source = source,
      remoteId = remoteId,
      syncStatus = syncStatus,
    )
  }
}
