package com.example.ui.components

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.Category
import com.example.domain.model.CategoryDefaults
import com.example.domain.model.ClipContentType
import com.example.domain.usecase.SearchSortOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Clean single-layer M3 card container with a subtle hairline border instead of heavy shadows
 * or nested card-in-card wrappers.
 */
@Composable
fun VaultSectionCard(
  modifier: Modifier = Modifier,
  containerColor: Color = MaterialTheme.colorScheme.surface,
  borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
  content: @Composable ColumnScope.() -> Unit,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.medium,
    color = containerColor,
    border = BorderStroke(1.dp, borderColor),
    tonalElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      content = content,
    )
  }
}

@Composable
fun SectionHeader(
  title: String,
  modifier: Modifier = Modifier,
  trailingText: String? = null,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onBackground,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    if (trailingText != null) {
      Text(
        text = trailingText,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
      )
    } else if (actionLabel != null && onAction != null) {
      TextButton(onClick = onAction) {
        Text(text = actionLabel, style = MaterialTheme.typography.labelLarge)
      }
    }
  }
}

/**
 * Accessible status pill that always pairs an icon with a label rather than relying on color alone.
 */
@Composable
fun StatusPill(
  icon: ImageVector,
  label: String,
  containerColor: Color,
  contentColor: Color,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(999.dp),
    color = containerColor,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = contentColor,
        modifier = Modifier.size(14.dp),
      )
      Spacer(modifier = Modifier.width(5.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = contentColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/**
 * Ensures technical identifiers, code, URLs, SHA-256 hashes, and API tokens stay properly LTR
 * even when the surrounding UI is in Arabic RTL mode.
 */
@Composable
fun LtrText(
  text: String,
  modifier: Modifier = Modifier,
  style: TextStyle = LocalTextStyle.current,
  color: Color = Color.Unspecified,
  maxLines: Int = Int.MAX_VALUE,
  overflow: TextOverflow = TextOverflow.Clip,
) {
  CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Text(
      text = text,
      modifier = modifier,
      style = style.copy(textDirection = TextDirection.Ltr),
      color = color,
      maxLines = maxLines,
      overflow = overflow,
    )
  }
}

@Composable
fun localizedCategoryName(category: Category?): String {
  if (category == null) return stringResource(R.string.cat_personal)
  if (!category.isDefault) return category.name
  return when (category.id) {
    CategoryDefaults.PERSONAL_ID -> stringResource(R.string.cat_personal)
    CategoryDefaults.WORK_ID -> stringResource(R.string.cat_work)
    CategoryDefaults.PROGRAMMING_ID -> stringResource(R.string.cat_programming)
    CategoryDefaults.LINKS_ID -> stringResource(R.string.cat_links)
    CategoryDefaults.NOTES_ID -> stringResource(R.string.cat_notes)
    CategoryDefaults.IMPORTANT_ID -> stringResource(R.string.cat_important)
    else -> category.name
  }
}

@Composable
fun localizedContentTypeLabel(type: ClipContentType): String =
  when (type) {
    ClipContentType.TEXT -> stringResource(R.string.type_text)
    ClipContentType.LINK -> stringResource(R.string.type_link)
    ClipContentType.CODE -> stringResource(R.string.type_code)
    ClipContentType.NOTE -> stringResource(R.string.type_note)
    ClipContentType.ADDRESS -> stringResource(R.string.type_address)
  }

@Composable
fun localizedSortOrderLabel(order: SearchSortOrder): String =
  when (order) {
    SearchSortOrder.RELEVANCE -> stringResource(R.string.sort_relevance)
    SearchSortOrder.NEWEST -> stringResource(R.string.sort_newest)
    SearchSortOrder.OLDEST -> stringResource(R.string.sort_oldest)
  }

private val shortDateFormatter =
  object : ThreadLocal<Pair<String, SimpleDateFormat>>() {
    override fun initialValue(): Pair<String, SimpleDateFormat> {
      val locale = Locale.getDefault()
      return locale.toLanguageTag() to SimpleDateFormat("MMM d", locale)
    }
  }

private fun formatShortDate(timestamp: Long): String {
  val locale = Locale.getDefault()
  val tag = locale.toLanguageTag()
  val cached = shortDateFormatter.get()
  val fmt =
    if (cached != null && cached.first == tag) {
      cached.second
    } else {
      SimpleDateFormat("MMM d", locale).also { shortDateFormatter.set(tag to it) }
    }
  return fmt.format(Date(timestamp))
}

@Composable
fun localizedRelativeTime(timestamp: Long): String {
  val diff = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
  return when {
    diff < 60_000L -> stringResource(R.string.time_now)
    diff < 3_600_000L -> stringResource(R.string.time_minutes_ago, (diff / 60_000L).toInt())
    diff < 86_400_000L -> stringResource(R.string.time_hours_ago, (diff / 3_600_000L).toInt())
    else -> formatShortDate(timestamp)
  }
}

sealed interface UiText {
  data class Resource(@StringRes val resId: Int, val args: List<Any> = emptyList()) : UiText
  data class Dynamic(val text: String) : UiText

  fun resolve(context: Context): String =
    when (this) {
      is Resource ->
        if (args.isEmpty()) context.getString(resId)
        else context.getString(resId, *args.toTypedArray())
      is Dynamic -> text
    }
}

