package com.example

import com.example.domain.model.CategoryDefaults
import com.example.domain.model.ClipContentType
import com.example.domain.model.ClipItem
import com.example.domain.usecase.SearchFilter
import com.example.domain.usecase.SearchSortOrder
import com.example.domain.usecase.SmartSearchUseCase
import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleUnitTest {

  private val searchUseCase = SmartSearchUseCase()

  @Test
  fun `smart search handles Arabic RTL text, long text, and relevance ranking`() {
    val longBody = "Intro " + "word ".repeat(250) + "target_token_xyz"
    val clips =
      listOf(
        ClipItem(
          id = 1L,
          title = "ملاحظة مهمة",
          content = "موعد الاجتماع الساعة العاشرة صباحًا",
          contentHash = "h1",
          contentType = ClipContentType.NOTE,
          categoryId = CategoryDefaults.NOTES_ID,
          tags = listOf("عمل"),
          updatedAt = 1000L,
        ),
        ClipItem(
          id = 2L,
          title = "Long Log",
          content = longBody,
          contentHash = "h2",
          contentType = ClipContentType.NOTE,
          categoryId = CategoryDefaults.PERSONAL_ID,
          updatedAt = 2000L,
        ),
        ClipItem(
          id = 3L,
          title = "Exact Match",
          content = "Kotlin Coroutines Flow",
          contentHash = "h3",
          contentType = ClipContentType.TEXT,
          categoryId = CategoryDefaults.PROGRAMMING_ID,
          updatedAt = 3000L,
        ),
      )

    val arabicResults =
      searchUseCase(clips, SearchFilter(query = "الاجتماع", sortOrder = SearchSortOrder.RELEVANCE), nowMillis = 4000L)
    assertEquals(1, arabicResults.size)
    assertEquals(1L, arabicResults.first().id)

    val longResults =
      searchUseCase(clips, SearchFilter(query = "target_token_xyz"), nowMillis = 4000L)
    assertEquals(1, longResults.size)
    assertEquals(2L, longResults.first().id)
  }
}
