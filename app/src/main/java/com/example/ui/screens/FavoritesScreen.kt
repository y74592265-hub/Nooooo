package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.Category
import com.example.domain.model.ClipItem
import com.example.ui.components.ClipCard
import com.example.ui.components.EmptyStateView
import com.example.ui.components.SectionHeader
import com.example.ui.theme.AppSpacing

@Composable
fun FavoritesScreen(
  favorites: List<ClipItem>,
  categories: List<Category>,
  onOpenClipDetails: (Long) -> Unit,
  onCopyClip: (ClipItem) -> Unit,
  onTogglePin: (ClipItem) -> Unit,
  onToggleFavorite: (ClipItem) -> Unit,
  modifier: Modifier = Modifier,
) {
  val categoryMap = remember(categories) { categories.associateBy { it.id } }

  Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
    if (favorites.isEmpty()) {
      EmptyStateView(
        title = stringResource(R.string.empty_favorites_title),
        subtitle = stringResource(R.string.empty_favorites_subtitle),
        showArtwork = false,
        icon = Icons.Filled.StarOutline,
      )
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize().widthIn(max = AppSpacing.maxContentWidth),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        item {
          SectionHeader(
            title = stringResource(R.string.nav_favorites),
            trailingText = "${favorites.size}",
          )
        }
        items(favorites, key = { it.id }) { clip ->
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
