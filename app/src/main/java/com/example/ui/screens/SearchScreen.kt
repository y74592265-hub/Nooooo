package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.Category
import com.example.domain.model.ClipContentType
import com.example.domain.model.ClipItem
import com.example.domain.usecase.DateFilter
import com.example.domain.usecase.SearchFilter
import com.example.domain.usecase.SearchSortOrder
import com.example.ui.components.ClipCard
import com.example.ui.components.EmptyStateView
import com.example.ui.components.localizedCategoryName
import com.example.ui.components.localizedContentTypeLabel
import com.example.ui.components.localizedSortOrderLabel
import com.example.ui.theme.AppSpacing

@Composable
fun SearchScreen(
  filter: SearchFilter,
  results: List<ClipItem>,
  categories: List<Category>,
  onQueryChange: (String) -> Unit,
  onCategoryChange: (Long?) -> Unit,
  onTypeChange: (ClipContentType?) -> Unit,
  onDateFilterChange: (DateFilter) -> Unit,
  onSortOrderChange: (SearchSortOrder) -> Unit,
  onOpenClipDetails: (Long) -> Unit,
  onCopyClip: (ClipItem) -> Unit,
  onTogglePin: (ClipItem) -> Unit,
  onToggleFavorite: (ClipItem) -> Unit,
  modifier: Modifier = Modifier,
) {
  val categoryMap = remember(categories) { categories.associateBy { it.id } }
  val hasActiveFilters =
    filter.query.isNotEmpty() ||
      filter.categoryId != null ||
      filter.contentType != null ||
      filter.dateFilter != DateFilter.ALL ||
      filter.sortOrder != SearchSortOrder.RELEVANCE

  Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
    Column(modifier = Modifier.fillMaxSize().widthIn(max = AppSpacing.maxContentWidth)) {
      OutlinedTextField(
        value = filter.query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.search_placeholder)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
          if (filter.query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }) {
              Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
            }
          }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        colors =
          OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
          ),
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("search_input"),
      )

      // Date Range & Sort Order Row
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        DateFilter.entries.forEach { df ->
          val label =
            when (df) {
              DateFilter.ALL -> stringResource(R.string.filter_all)
              DateFilter.TODAY -> stringResource(R.string.filter_today)
              DateFilter.LAST_7_DAYS -> stringResource(R.string.filter_week)
              DateFilter.LAST_30_DAYS -> stringResource(R.string.filter_month)
            }
          FilterChip(
            selected = filter.dateFilter == df,
            onClick = { onDateFilterChange(df) },
            label = { Text(label) },
          )
        }
        SearchSortOrder.entries.forEach { order ->
          FilterChip(
            selected = filter.sortOrder == order,
            onClick = { onSortOrderChange(order) },
            label = { Text(localizedSortOrderLabel(order)) },
          )
        }
      }

      // Content Type & Category Filters Row
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        ClipContentType.entries.forEach { type ->
          FilterChip(
            selected = filter.contentType == type,
            onClick = { onTypeChange(if (filter.contentType == type) null else type) },
            label = { Text(localizedContentTypeLabel(type)) },
          )
        }
        categories.forEach { cat ->
          FilterChip(
            selected = filter.categoryId == cat.id,
            onClick = { onCategoryChange(if (filter.categoryId == cat.id) null else cat.id) },
            leadingIcon = {
              Box(
                modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(cat.colorHex))
              )
            },
            label = { Text(localizedCategoryName(cat)) },
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.search_results_count, results.size),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hasActiveFilters) {
          TextButton(
            onClick = {
              onQueryChange("")
              onCategoryChange(null)
              onTypeChange(null)
              onDateFilterChange(DateFilter.ALL)
              onSortOrderChange(SearchSortOrder.RELEVANCE)
            }
          ) {
            Text(stringResource(R.string.search_clear_filters))
          }
        }
      }

      if (results.isEmpty()) {
        EmptyStateView(
          title = stringResource(R.string.search_no_results),
          subtitle = stringResource(R.string.search_no_results_hint),
          showArtwork = false,
          icon = Icons.Filled.SearchOff,
        )
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          items(results, key = { it.id }) { clip ->
            ClipCard(
              clip = clip,
              category = categoryMap[clip.categoryId],
              onClick = { onOpenClipDetails(clip.id) },
              onCopyClick = { onCopyClip(clip) },
              onPinToggle = { onTogglePin(clip) },
              onFavoriteToggle = { onToggleFavorite(clip) },
            )
          }
          item { Spacer(modifier = Modifier.height(76.dp)) }
        }
      }
    }
  }
}
