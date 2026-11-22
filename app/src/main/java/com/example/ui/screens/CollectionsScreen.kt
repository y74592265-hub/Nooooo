package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.Category
import com.example.domain.model.ClipItem
import com.example.ui.components.ClipCard
import com.example.ui.components.EmptyStateView
import com.example.ui.components.localizedCategoryName
import com.example.ui.theme.AppSpacing

private val FOLDER_COLORS =
  listOf(
    0xFF1A56DBL,
    0xFF7C3AEDL,
    0xFF0E7490L,
    0xFF0F766EL,
    0xFFD97706L,
    0xFFDC2626L,
    0xFFDB2777L,
    0xFF475569L,
  )

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CollectionsScreen(
  categories: List<Category>,
  clips: List<ClipItem>,
  initialCategoryId: Long? = null,
  onCreateCategory: (String, Long) -> Unit,
  onRenameCategory: (Long, String, Long) -> Unit,
  onDeleteCategory: (Long) -> Unit,
  onOpenClipDetails: (Long) -> Unit,
  onCopyClip: (ClipItem) -> Unit,
  onTogglePin: (ClipItem) -> Unit,
  onToggleFavorite: (ClipItem) -> Unit,
  modifier: Modifier = Modifier,
) {
  var openedCategoryId by remember(initialCategoryId) { mutableStateOf(initialCategoryId) }
  var showCreateDialog by remember { mutableStateOf(false) }
  var editingCategory by remember { mutableStateOf<Category?>(null) }

  if (openedCategoryId != null) {
    BackHandler { openedCategoryId = null }
  }

  Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
    Column(modifier = Modifier.fillMaxSize().widthIn(max = AppSpacing.maxContentWidth)) {
      if (openedCategoryId == null) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = stringResource(R.string.nav_collections),
            style = MaterialTheme.typography.titleLarge,
          )
          Button(
            onClick = { showCreateDialog = true },
            modifier = Modifier.testTag("create_collection_button"),
          ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.collections_new_folder))
          }
        }

        LazyVerticalGrid(
          columns = GridCells.Adaptive(minSize = 156.dp),
          contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 84.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
          modifier = Modifier.fillMaxSize(),
        ) {
          items(categories, key = { it.id }) { cat ->
            var menuExpanded by remember { mutableStateOf(false) }
            val folderColor = Color(cat.colorHex)
            Surface(
              onClick = { openedCategoryId = cat.id },
              shape = MaterialTheme.shapes.medium,
              color = MaterialTheme.colorScheme.surface,
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
              modifier = Modifier.fillMaxWidth().testTag("collection_card_${cat.id}"),
            ) {
              Column(modifier = Modifier.padding(16.dp)) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Box(
                    modifier =
                      Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(folderColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                  ) {
                    Icon(
                      imageVector = Icons.Filled.Folder,
                      contentDescription = localizedCategoryName(cat),
                      tint = folderColor,
                    )
                  }
                  Box {
                    IconButton(
                      onClick = { menuExpanded = true },
                      modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                      Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.collections_options),
                      )
                    }
                    DropdownMenu(
                      expanded = menuExpanded,
                      onDismissRequest = { menuExpanded = false },
                    ) {
                      DropdownMenuItem(
                        text = { Text(stringResource(R.string.collections_rename_color)) },
                        onClick = {
                          menuExpanded = false
                          editingCategory = cat
                        },
                      )
                      if (!cat.isDefault) {
                        DropdownMenuItem(
                          text = {
                            Text(
                              stringResource(R.string.action_delete),
                              color = MaterialTheme.colorScheme.error,
                            )
                          },
                          onClick = {
                            menuExpanded = false
                            onDeleteCategory(cat.id)
                          },
                        )
                      }
                    }
                  }
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                  text = localizedCategoryName(cat),
                  style = MaterialTheme.typography.titleMedium,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                  shape = RoundedCornerShape(999.dp),
                  color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                  Text(
                    text = stringResource(R.string.collections_clip_count, cat.clipCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                  )
                }
              }
            }
          }
        }
      } else {
        val currentCat = categories.find { it.id == openedCategoryId }
        val categoryClips = clips.filter { it.categoryId == openedCategoryId }

        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          IconButton(
            onClick = { openedCategoryId = null },
            modifier = Modifier.minimumInteractiveComponentSize(),
          ) {
            Icon(
              Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.action_back),
            )
          }
          Box(
            modifier =
              Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(Color(currentCat?.colorHex ?: 0xFF1A56DB))
          )
          Spacer(Modifier.width(8.dp))
          Text(
            text = localizedCategoryName(currentCat),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(end = 8.dp),
          ) {
            Text(
              text = stringResource(R.string.collections_clip_count, categoryClips.size),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
          }
        }

        if (categoryClips.isEmpty()) {
          EmptyStateView(
            title = stringResource(R.string.collections_empty_title),
            subtitle = stringResource(R.string.collections_empty_subtitle),
            showArtwork = false,
            icon = Icons.Filled.FolderOpen,
          )
        } else {
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            items(categoryClips, key = { it.id }) { clip ->
              ClipCard(
                clip = clip,
                category = currentCat,
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

  if (showCreateDialog) {
    CategoryEditorDialog(
      title = stringResource(R.string.collections_create_title),
      initialName = "",
      initialColor = FOLDER_COLORS.first(),
      onDismiss = { showCreateDialog = false },
      onConfirm = { name, color ->
        showCreateDialog = false
        onCreateCategory(name, color)
      },
    )
  }

  editingCategory?.let { cat ->
    CategoryEditorDialog(
      title = stringResource(R.string.collections_edit_title),
      initialName = localizedCategoryName(cat),
      initialColor = cat.colorHex,
      onDismiss = { editingCategory = null },
      onConfirm = { name, color ->
        editingCategory = null
        onRenameCategory(cat.id, name, color)
      },
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryEditorDialog(
  title: String,
  initialName: String,
  initialColor: Long,
  onDismiss: () -> Unit,
  onConfirm: (String, Long) -> Unit,
) {
  var name by remember { mutableStateOf(initialName) }
  var selectedColor by remember { mutableLongStateOf(initialColor) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text(stringResource(R.string.collections_folder_name)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("collection_name_input"),
        )
        Text(
          text = stringResource(R.string.collections_folder_color),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          FOLDER_COLORS.forEach { hex ->
            Box(
              modifier =
                Modifier
                  .size(34.dp)
                  .clip(CircleShape)
                  .background(Color(hex))
                  .then(
                    if (selectedColor == hex) {
                      Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    } else Modifier
                  )
                  .clickable { selectedColor = hex }
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = { onConfirm(name, selectedColor) },
        enabled = name.isNotBlank(),
        modifier = Modifier.testTag("save_collection_button"),
      ) {
        Text(stringResource(R.string.action_save))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
    },
  )
}
