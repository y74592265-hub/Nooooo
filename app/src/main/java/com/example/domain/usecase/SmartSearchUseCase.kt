package com.example.domain.usecase

import com.example.domain.intelligence.ArabicTextNormalizer
import com.example.domain.model.ClipContentType
import com.example.domain.model.ClipItem
import javax.inject.Inject

enum class DateFilter {
  ALL,
  TODAY,
  LAST_7_DAYS,
  LAST_30_DAYS,
}

enum class SearchSortOrder {
  RELEVANCE,
  NEWEST,
  OLDEST,
}

data class SearchFilter(
  val query: String = "",
  val categoryId: Long? = null,
  val contentType: ClipContentType? = null,
  val dateFilter: DateFilter = DateFilter.ALL,
  val favoritesOnly: Boolean = false,
  val sortOrder: SearchSortOrder = SearchSortOrder.RELEVANCE,
)

class SmartSearchUseCase @Inject constructor() {

  operator fun invoke(
    clips: List<ClipItem>,
    filter: SearchFilter,
    nowMillis: Long = System.currentTimeMillis(),
  ): List<ClipItem> {
    val q = filter.query.trim()
    val minTimestamp =
      when (filter.dateFilter) {
        DateFilter.ALL -> 0L
        DateFilter.TODAY -> nowMillis - 24L * 60L * 60L * 1000L
        DateFilter.LAST_7_DAYS -> nowMillis - 7L * 24L * 60L * 60L * 1000L
        DateFilter.LAST_30_DAYS -> nowMillis - 30L * 24L * 60L * 60L * 1000L
      }

    val filtered =
      clips.filter { clip ->
        if (filter.categoryId != null && clip.categoryId != filter.categoryId) return@filter false
        if (filter.contentType != null && clip.contentType != filter.contentType) return@filter false
        if (clip.updatedAt < minTimestamp) return@filter false
        if (filter.favoritesOnly && !clip.isFavorite) return@filter false
        if (q.isEmpty()) return@filter true
        ArabicTextNormalizer.normalizedContains(clip.title, q) ||
          ArabicTextNormalizer.normalizedContains(clip.content, q) ||
          clip.tags.any { ArabicTextNormalizer.normalizedContains(it, q) }
      }

    return when (filter.sortOrder) {
      SearchSortOrder.NEWEST ->
        filtered.sortedWith(compareByDescending<ClipItem> { it.isPinned }.thenByDescending { it.updatedAt })
      SearchSortOrder.OLDEST -> filtered.sortedBy { it.updatedAt }
      SearchSortOrder.RELEVANCE -> {
        if (q.isEmpty()) {
          filtered.sortedWith(compareByDescending<ClipItem> { it.isPinned }.thenByDescending { it.updatedAt })
        } else {
          filtered.sortedWith(
            compareByDescending<ClipItem> { scoreRelevance(it, q) }
              .thenByDescending { it.isPinned }
              .thenByDescending { it.updatedAt }
          )
        }
      }
    }
  }

  private fun scoreRelevance(clip: ClipItem, query: String): Int {
    var score = 0
    if (clip.title.equals(query, ignoreCase = true)) score += 100
    else if (clip.title.startsWith(query, ignoreCase = true)) score += 70
    else if (ArabicTextNormalizer.normalizedContains(clip.title, query)) score += 45

    if (clip.tags.any { it.equals(query, ignoreCase = true) }) score += 60
    else if (clip.tags.any { ArabicTextNormalizer.normalizedContains(it, query) }) score += 30

    if (clip.content.startsWith(query, ignoreCase = true)) score += 40
    else if (ArabicTextNormalizer.normalizedContains(clip.content, query)) score += 20

    if (clip.isPinned) score += 10
    if (clip.isFavorite) score += 5
    return score
  }
}
