package com.example.domain.usecase

import com.example.core.clipboard.SystemClipboardHelper
import com.example.domain.model.ClipContentType
import com.example.domain.model.ClipSource
import com.example.domain.repository.CategoryRepository
import com.example.domain.repository.ClipRepository
import com.example.domain.repository.SaveClipResult
import javax.inject.Inject

class SaveClipUseCase
@Inject
constructor(
  private val clipRepository: ClipRepository,
  private val categoryRepository: CategoryRepository,
  private val clipboardHelper: SystemClipboardHelper,
) {
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
  ): SaveClipResult? {
    val trimmed = content.trim()
    if (trimmed.isEmpty()) return null
    categoryRepository.ensureDefaultCategories()
    val resolvedType = contentType ?: clipboardHelper.classifyContent(trimmed)
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
    )
  }
}
