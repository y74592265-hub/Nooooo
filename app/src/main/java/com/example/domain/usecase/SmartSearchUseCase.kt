package com.example.domain.usecase

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
    val q = filter.query.trim().lowercase()
    val minTimestamp =
      when (filter.dateFilter) {
        DateFilter.ALL -> 0L
        DateFilter.TODAY -> nowMillis - 24L * 60L * 60L * 1000L
        DateFilter.LAST_7_DAYS -> nowMillis - 7L * 24L * 60L * 60L * 1000L
        DateFilter.LAST_30_DAYS -> nowMillis - 30L * 24L * 60L * 60L * 1000L
      }

    val filtered =
      clips.filter { clip ->
        val matchesCategory = filter.categoryId == null || clip.categoryId == filter.categoryId
        val matchesType = filter.contentType == null || clip.contentType == filter.contentType
        val matchesDate = clip.updatedAt >= minTimestamp
        val matchesFav = !filter.favoritesOnly || clip.isFavorite
        val matchesQuery =
          q.isEmpty() ||
            clip.title.lowercase().contains(q) ||
            clip.content.lowercase().contains(q) ||
            clip.tags.any { it.lowercase().contains(q) }
        matchesCategory && matchesType && matchesDate && matchesFav && matchesQuery
      }

    return when (filter.sortOrder) {
      SearchSortOrder.NEWEST -> filtered.sortedWith(compareByDescending<ClipItem> { it.isPinned }.thenByDescending { it.updatedAt })
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

  private fun scoreRelevance(clip: ClipItem, queryLower: String): Int {
    var score = 0
    val titleLower = clip.title.lowercase()
    val contentLower = clip.content.lowercase()
    if (titleLower == queryLower) score += 100
    else if (titleLower.startsWith(queryLower)) score += 70
    else if (titleLower.contains(queryLower)) score += 45

    if (clip.tags.any { it.lowercase() == queryLower }) score += 60
    else if (clip.tags.any { it.lowercase().contains(queryLower) }) score += 30

    if (contentLower.startsWith(queryLower)) score += 40
    else if (contentLower.contains(queryLower)) score += 20

    if (clip.isPinned) score += 10
    if (clip.isFavorite) score += 5
    return score
  }
}
