package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.Category
import com.example.domain.model.ClipItem
import com.example.ui.components.ClipCard
import com.example.ui.components.EmptyStateView
import com.example.ui.components.localizedCategoryName
import com.example.ui.theme.AppSpacing

@Composable
fun AllClipsScreen(
  clips: List<ClipItem>,
  categories: List<Category>,
  selectedClipIds: Set<Long>,
  onToggleSelection: (Long) -> Unit,
  onSelectAll: (List<Long>) -> Unit,
  onClearSelection: () -> Unit,
  onBulkDelete: (List<Long>) -> Unit,
  onBulkMoveCategory: (Long) -> Unit,
  onOpenClipDetails: (Long) -> Unit,
  onCopyClip: (ClipItem) -> Unit,
  onTogglePin: (ClipItem) -> Unit,
  onToggleFavorite: (ClipItem) -> Unit,
  onAddNewClip: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var filterCategoryId by remember { mutableStateOf<Long?>(null) }
  var showMoveMenu by remember { mutableStateOf(false) }
  var showDeleteConfirm by remember { mutableStateOf(false) }

  val categoryMap = remember(categories) { categories.associateBy { it.id } }
  val filtered =
    remember(clips, filterCategoryId) {
      if (filterCategoryId == null) clips else clips.filter { it.categoryId == filterCategoryId }
    }
  val inSelectionMode = selectedClipIds.isNotEmpty()

  Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
    Column(modifier = Modifier.fillMaxSize().widthIn(max = AppSpacing.maxContentWidth)) {
      AnimatedVisibility(visible = inSelectionMode) {
        Surface(
          color = MaterialTheme.colorScheme.primaryContainer,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
          shape = MaterialTheme.shapes.medium,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              IconButton(
                onClick = onClearSelection,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("clear_selection_btn"),
              ) {
                Icon(
                  Icons.Filled.Close,
                  contentDescription = stringResource(R.string.action_clear_selection),
                )
              }
              Text(
                text = stringResource(R.string.selected_count, selectedClipIds.size),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
              )
            }
            Row {
              IconButton(
                onClick = { onSelectAll(filtered.map { it.id }) },
                modifier = Modifier.minimumInteractiveComponentSize().testTag("select_all_btn"),
              ) {
                Icon(
                  Icons.Filled.SelectAll,
                  contentDescription = stringResource(R.string.action_select_all),
                )
              }
              Box {
                IconButton(
                  onClick = { showMoveMenu = true },
                  modifier = Modifier.minimumInteractiveComponentSize().testTag("bulk_move_btn"),
                ) {
                  Icon(
                    Icons.Filled.DriveFileMove,
                    contentDescription = stringResource(R.string.bulk_move_to_collection),
                  )
                }
                DropdownMenu(expanded = showMoveMenu, onDismissRequest = { showMoveMenu = false }) {
                  categories.forEach { cat ->
                    DropdownMenuItem(
                      text = { Text(localizedCategoryName(cat)) },
                      onClick = {
                        showMoveMenu = false
                        onBulkMoveCategory(cat.id)
                      },
                    )
                  }
                }
              }
              IconButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.minimumInteractiveComponentSize().testTag("bulk_delete_btn"),
              ) {
                Icon(
                  Icons.Filled.Delete,
                  contentDescription = stringResource(R.string.action_delete),
                  tint = MaterialTheme.colorScheme.error,
                )
              }
            }
          }
        }
      }

      if (!inSelectionMode) {
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState())
              .padding(horizontal = 16.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          FilterChip(
            selected = filterCategoryId == null,
            onClick = { filterCategoryId = null },
            label = { Text("${stringResource(R.string.filter_all)} (${clips.size})") },
          )
          categories.forEach { cat ->
            FilterChip(
              selected = filterCategoryId == cat.id,
              onClick = { filterCategoryId = if (filterCategoryId == cat.id) null else cat.id },
              leadingIcon = {
                Box(
                  modifier =
                    Modifier
                      .size(8.dp)
                      .clip(CircleShape)
                      .background(Color(cat.colorHex))
                )
              },
              label = { Text("${localizedCategoryName(cat)} (${cat.clipCount})") },
            )
          }
        }
      }

      if (filtered.isEmpty()) {
        EmptyStateView(
          title = stringResource(R.string.empty_clips_title),
          subtitle =
            if (filterCategoryId != null) stringResource(R.string.no_clips_in_filter)
            else stringResource(R.string.empty_clips_subtitle),
          showArtwork = filterCategoryId == null,
          icon = Icons.Filled.ContentPaste,
          actionLabel = stringResource(R.string.action_add_clip),
          onAction = onAddNewClip,
        )
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          items(filtered, key = { it.id }) { clip ->
            ClipCard(
              clip = clip,
              category = categoryMap[clip.categoryId],
              isSelected = selectedClipIds.contains(clip.id),
              inSelectionMode = inSelectionMode,
              onClick = {
                if (inSelectionMode) onToggleSelection(clip.id) else onOpenClipDetails(clip.id)
              },
              onLongClick = { onToggleSelection(clip.id) },
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

  if (showDeleteConfirm) {
    AlertDialog(
      onDismissRequest = { showDeleteConfirm = false },
      title = { Text(stringResource(R.string.action_delete)) },
      text = { Text(stringResource(R.string.bulk_delete_confirm_msg, selectedClipIds.size)) },
      confirmButton = {
        TextButton(
          onClick = {
            showDeleteConfirm = false
            onBulkDelete(selectedClipIds.toList())
          }
        ) {
          Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = { showDeleteConfirm = false }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    )
  }
}
